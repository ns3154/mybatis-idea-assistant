package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 只在内存中生成候选文件；不读写项目、不连接数据库。
 */
public final class MyBatisGenerationEngine {
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "join", "left", "right", "inner", "outer",
            "group", "order", "having", "limit", "offset", "insert", "update",
            "delete", "values", "set", "and", "or", "on", "as", "distinct",
            "case", "when", "then", "else", "end", "null", "is", "in", "exists",
            "like", "table", "user", "key", "primary", "foreign", "index", "constraint");

    private MyBatisGenerationEngine() {
    }

    public static @NotNull MyBatisGenerationBundle generate(
            @NotNull MyBatisGenerationRequest request) {
        ProgressManager.checkCanceled();
        MyBatisGenerationConfiguration configuration = request.configuration();
        String tableName = request.table().name();
        String logicalTableName = stripPrefix(tableName, configuration.tablePrefix());
        String entityName = MyBatisGenerationNames.upperCamel(logicalTableName)
                + configuration.entitySuffix();
        MyBatisGenerationNames.requireJavaIdentifier(entityName);
        List<ColumnModel> columns = columns(request.table(), configuration);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.engine.error.columns.empty", tableName));
        }
        validatePropertyNames(columns);

        List<MyBatisGeneratedArtifact> artifacts = new ArrayList<>();
        for (MyBatisGenerationArtifactKind kind : MyBatisGenerationArtifactKind.values()) {
            ProgressManager.checkCanceled();
            if (!configuration.artifacts().contains(kind)) {
                continue;
            }
            artifacts.add(switch (kind) {
                case ENTITY -> entity(request, entityName, columns);
                case MAPPER -> mapper(request, entityName, columns);
                case XML -> xml(request, entityName, columns);
                case SERVICE -> service(request, entityName, columns);
            });
        }
        return new MyBatisGenerationBundle(entityName, artifacts);
    }

    private static @NotNull MyBatisGeneratedArtifact entity(
            @NotNull MyBatisGenerationRequest request,
            @NotNull String entityName,
            @NotNull List<ColumnModel> columns) {
        MyBatisGenerationConfiguration configuration = request.configuration();
        String packageName = configuration.basePackage() + ".entity";
        Set<String> imports = new TreeSet<>();
        columns.forEach(column -> column.type.importName().ifPresent(imports::add));
        boolean plus = configuration.templateGroup() == MyBatisGenerationTemplateGroup.MYBATIS_PLUS;
        if (plus) {
            imports.add("com.baomidou.mybatisplus.annotation.TableField");
            imports.add("com.baomidou.mybatisplus.annotation.TableId");
            imports.add("com.baomidou.mybatisplus.annotation.TableName");
            if (columns.stream().anyMatch(column -> column.column.autoIncrement()
                    && column.column.primaryKey())) {
                imports.add("com.baomidou.mybatisplus.annotation.IdType");
            }
            columns.forEach(column -> column.override.typeHandler().ifPresent(imports::add));
        }
        validateTypeNames(entityName, referencedEntityTypes(columns, plus));
        String baseId = markerBase(configuration, entityName, "entity");
        String header = javaHeader(packageName, imports);
        StringBuilder declaration = new StringBuilder();
        if (configuration.generateComments()) {
            declaration.append(javaDoc(request.table().comment().orElseGet(() ->
                    MyBatisAssistantBundle.message(
                            "generator.engine.comment.database.table",
                            request.table().name()))));
        }
        if (plus) {
            declaration.append("@TableName(\"")
                    .append(javaString(request.table().name()))
                    .append("\")\n");
        }
        declaration.append("public class ").append(entityName).append(" {\n");

        StringBuilder members = new StringBuilder();
        for (ColumnModel column : columns) {
            ProgressManager.checkCanceled();
            if (configuration.generateComments() && column.column.comment().isPresent()) {
                members.append(indent(javaDoc(column.column.comment().orElseThrow()), 1));
            }
            if (plus) {
                appendPlusFieldAnnotation(members, column);
            }
            members.append("    private ").append(column.type.simpleType()).append(' ')
                    .append(column.propertyName).append(";\n\n");
            String accessor = Character.toUpperCase(column.propertyName.charAt(0))
                    + column.propertyName.substring(1);
            members.append("    public ").append(column.type.simpleType()).append(" get")
                    .append(accessor).append("() {\n")
                    .append("        return ").append(column.propertyName).append(";\n")
                    .append("    }\n\n")
                    .append("    public void set").append(accessor).append('(')
                    .append(column.type.simpleType()).append(' ').append(column.propertyName)
                    .append(") {\n")
                    .append("        this.").append(column.propertyName).append(" = ")
                    .append(column.propertyName).append(";\n")
                    .append("    }\n\n");
        }
        String content = region(MyBatisGeneratedRegion.Style.JAVA, baseId + ":header", header)
                + "\n"
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":declaration", declaration.toString())
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":members", indent(members.toString(), 1))
                + "\n    // " + MyBatisAssistantBundle.message(
                        "generator.engine.comment.entity.manual") + "\n"
                + "}\n";
        return artifact(
                MyBatisGenerationArtifactKind.ENTITY,
                javaPath(configuration, packageName, entityName + ".java"),
                content,
                baseId,
                "header", "declaration", "members");
    }

    private static @NotNull MyBatisGeneratedArtifact mapper(
            @NotNull MyBatisGenerationRequest request,
            @NotNull String entityName,
            @NotNull List<ColumnModel> columns) {
        MyBatisGenerationConfiguration configuration = request.configuration();
        String packageName = configuration.basePackage() + ".mapper";
        String mapperName = entityName + "Mapper";
        Set<String> imports = new TreeSet<>();
        imports.add(configuration.basePackage() + ".entity." + entityName);
        List<ColumnModel> keys = primaryKeys(columns);
        keys.forEach(column -> column.type.importName().ifPresent(imports::add));
        boolean plus = configuration.templateGroup() == MyBatisGenerationTemplateGroup.MYBATIS_PLUS;
        if (plus) {
            imports.add("com.baomidou.mybatisplus.core.mapper.BaseMapper");
        } else if (keys.size() > 1) {
            imports.add("org.apache.ibatis.annotations.Param");
        }
        imports.add("java.util.List");
        validateTypeNames(mapperName, mapperTypes(configuration, entityName, keys, plus));
        String baseId = markerBase(configuration, entityName, "mapper");
        String header = javaHeader(packageName, imports);
        String declaration = plus
                ? "public interface " + mapperName + " extends BaseMapper<" + entityName + "> {\n"
                : "public interface " + mapperName + " {\n";
        StringBuilder members = new StringBuilder();
        if (!plus) {
            members.append("    List<").append(entityName).append("> selectAll();\n\n")
                    .append("    int insert(").append(entityName).append(" entity);\n");
            if (!keys.isEmpty()) {
                members.append("\n    ").append(entityName).append(" selectByPrimaryKey(")
                        .append(parameterDeclaration(keys)).append(");\n\n")
                        .append("    int deleteByPrimaryKey(")
                        .append(parameterDeclaration(keys)).append(");\n");
            }
            if (!keys.isEmpty()
                    && columns.stream().anyMatch(column -> !column.column.primaryKey())) {
                members.append("\n    int updateByPrimaryKey(")
                        .append(entityName).append(" entity);\n");
            }
        }
        String content = region(MyBatisGeneratedRegion.Style.JAVA, baseId + ":header", header)
                + "\n"
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":declaration", declaration)
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":members", indent(members.toString(), 1))
                + "\n    // " + MyBatisAssistantBundle.message(
                        "generator.engine.comment.mapper.manual") + "\n"
                + "}\n";
        return artifact(
                MyBatisGenerationArtifactKind.MAPPER,
                javaPath(configuration, packageName, mapperName + ".java"),
                content,
                baseId,
                "header", "declaration", "members");
    }

    private static @NotNull MyBatisGeneratedArtifact service(
            @NotNull MyBatisGenerationRequest request,
            @NotNull String entityName,
            @NotNull List<ColumnModel> columns) {
        MyBatisGenerationConfiguration configuration = request.configuration();
        String packageName = configuration.basePackage() + ".service";
        String serviceName = entityName + "Service";
        String mapperName = entityName + "Mapper";
        Set<String> imports = new TreeSet<>();
        imports.add(configuration.basePackage() + ".entity." + entityName);
        imports.add(configuration.basePackage() + ".mapper." + mapperName);
        imports.add("java.util.List");
        List<ColumnModel> keys = primaryKeys(columns);
        keys.forEach(column -> column.type.importName().ifPresent(imports::add));
        boolean plus = configuration.templateGroup() == MyBatisGenerationTemplateGroup.MYBATIS_PLUS;
        if (plus) {
            imports.add("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl");
        }
        validateTypeNames(serviceName, serviceTypes(
                configuration, entityName, mapperName, keys, plus));
        String baseId = markerBase(configuration, entityName, "service");
        String header = javaHeader(packageName, imports);
        String declaration = plus
                ? "public class " + serviceName + " extends ServiceImpl<" + mapperName
                        + ", " + entityName + "> {\n"
                : "public class " + serviceName + " {\n";
        StringBuilder members = new StringBuilder();
        if (!plus) {
            members.append("    private final ").append(mapperName).append(" mapper;\n\n")
                    .append("    public ").append(serviceName).append('(').append(mapperName)
                    .append(" mapper) {\n")
                    .append("        this.mapper = mapper;\n")
                    .append("    }\n\n")
                    .append("    public List<").append(entityName).append("> findAll() {\n")
                    .append("        return mapper.selectAll();\n")
                    .append("    }\n\n")
                    .append("    public int create(").append(entityName).append(" entity) {\n")
                    .append("        return mapper.insert(entity);\n")
                    .append("    }\n");
            if (!keys.isEmpty()) {
                members.append("\n    public ").append(entityName).append(" findByPrimaryKey(")
                        .append(serviceParameterDeclaration(keys)).append(") {\n")
                        .append("        return mapper.selectByPrimaryKey(")
                        .append(parameterNames(keys)).append(");\n")
                        .append("    }\n\n")
                        .append("    public int deleteByPrimaryKey(")
                        .append(serviceParameterDeclaration(keys)).append(") {\n")
                        .append("        return mapper.deleteByPrimaryKey(")
                        .append(parameterNames(keys)).append(");\n")
                        .append("    }\n");
            }
            if (!keys.isEmpty()
                    && columns.stream().anyMatch(column -> !column.column.primaryKey())) {
                members.append("\n    public int update(").append(entityName)
                        .append(" entity) {\n")
                        .append("        return mapper.updateByPrimaryKey(entity);\n")
                        .append("    }\n");
            }
        }
        String content = region(MyBatisGeneratedRegion.Style.JAVA, baseId + ":header", header)
                + "\n"
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":declaration", declaration)
                + region(MyBatisGeneratedRegion.Style.JAVA,
                        baseId + ":members", indent(members.toString(), 1))
                + "\n    // " + MyBatisAssistantBundle.message(
                        "generator.engine.comment.service.manual") + "\n"
                + "}\n";
        return artifact(
                MyBatisGenerationArtifactKind.SERVICE,
                javaPath(configuration, packageName, serviceName + ".java"),
                content,
                baseId,
                "header", "declaration", "members");
    }

    private static @NotNull MyBatisGeneratedArtifact xml(
            @NotNull MyBatisGenerationRequest request,
            @NotNull String entityName,
            @NotNull List<ColumnModel> columns) {
        MyBatisGenerationConfiguration configuration = request.configuration();
        String mapperName = entityName + "Mapper";
        String mapperPackage = configuration.basePackage() + ".mapper." + mapperName;
        String entityPackage = configuration.basePackage() + ".entity." + entityName;
        String baseId = markerBase(configuration, entityName, "xml");
        String header = "<mapper namespace=\"" + xmlAttribute(mapperPackage) + "\">\n";
        String table = qualifiedTable(request.table(), request.dialect(), configuration);
        List<ColumnModel> keys = primaryKeys(columns);
        StringBuilder body = new StringBuilder();
        if (configuration.generateComments()) {
            body.append("    <!-- ").append(xmlComment(
                    request.table().comment().orElseGet(() -> MyBatisAssistantBundle.message(
                            "generator.engine.comment.database.table",
                            request.table().name())))).append(" -->\n");
        }
        body.append("    <resultMap id=\"BaseResultMap\" type=\"")
                .append(xmlAttribute(entityPackage)).append("\">\n");
        for (ColumnModel column : columns) {
            String tag = column.column.primaryKey() ? "id" : "result";
            body.append("        <").append(tag)
                    .append(" column=\"").append(xmlAttribute(column.column.name()))
                    .append("\" property=\"").append(xmlAttribute(column.propertyName))
                    .append("\" jdbcType=\"").append(jdbcTypeName(column.column.jdbcType()))
                    .append('"');
            column.override.typeHandler().ifPresent(handler -> body.append(" typeHandler=\"")
                    .append(xmlAttribute(handler)).append('"'));
            body.append("/>\n");
        }
        body.append("    </resultMap>\n\n")
                .append("    <sql id=\"Base_Column_List\">\n        ")
                .append(columns.stream()
                        .map(column -> sqlIdentifier(
                                column.column.name(), request.dialect(), configuration))
                        .collect(java.util.stream.Collectors.joining(", ")))
                .append("\n    </sql>\n\n")
                .append("    <select id=\"selectAll\" resultMap=\"BaseResultMap\">\n")
                .append("        SELECT <include refid=\"Base_Column_List\"/> FROM ")
                .append(table).append("\n    </select>\n");
        if (!keys.isEmpty()) {
            body.append("\n    <select id=\"selectByPrimaryKey\" resultMap=\"BaseResultMap\">\n")
                    .append("        SELECT <include refid=\"Base_Column_List\"/> FROM ")
                    .append(table).append(" WHERE ").append(whereClause(keys, request))
                    .append("\n    </select>\n");
        }
        appendInsert(body, request, columns, keys, table);
        appendUpdate(body, request, columns, keys, table);
        if (!keys.isEmpty()) {
            body.append("\n    <delete id=\"deleteByPrimaryKey\">\n")
                    .append("        DELETE FROM ").append(table).append(" WHERE ")
                    .append(whereClause(keys, request)).append("\n    </delete>\n");
        }
        String content = region(MyBatisGeneratedRegion.Style.XML, baseId + ":header", header)
                + region(MyBatisGeneratedRegion.Style.XML,
                        baseId + ":statements", indent(body.toString(), 1))
                + "\n    <!-- " + MyBatisAssistantBundle.message(
                        "generator.engine.comment.xml.manual") + " -->\n"
                + "</mapper>\n";
        String path = configuration.resourceRoot() + "/mapper/" + mapperName + ".xml";
        return artifact(
                MyBatisGenerationArtifactKind.XML,
                path,
                content,
                baseId,
                "header", "statements");
    }

    private static void appendInsert(
            @NotNull StringBuilder body,
            @NotNull MyBatisGenerationRequest request,
            @NotNull List<ColumnModel> columns,
            @NotNull List<ColumnModel> keys,
            @NotNull String table) {
        List<ColumnModel> inserted = columns.stream()
                .filter(column -> !column.column.autoIncrement())
                .toList();
        Optional<ColumnModel> generatedKey = keys.stream()
                .filter(column -> column.column.autoIncrement())
                .findFirst();
        body.append("\n    <insert id=\"insert\"");
        if (generatedKey.isPresent() && keys.size() == 1) {
            body.append(" useGeneratedKeys=\"true\" keyProperty=\"")
                    .append(xmlAttribute(generatedKey.orElseThrow().propertyName)).append('"');
        }
        body.append(">\n        INSERT INTO ").append(table);
        if (inserted.isEmpty()) {
            appendDefaultValuesInsert(body, request, keys);
        } else {
            body.append(" (")
                    .append(inserted.stream().map(column -> sqlIdentifier(
                                    column.column.name(), request.dialect(), request.configuration()))
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append(") VALUES (")
                    .append(inserted.stream().map(column -> parameter(column, false))
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append(")\n");
        }
        body.append("    </insert>\n");
    }

    private static void appendDefaultValuesInsert(
            @NotNull StringBuilder body,
            @NotNull MyBatisGenerationRequest request,
            @NotNull List<ColumnModel> keys) {
        switch (request.dialect()) {
            case MYSQL -> body.append(" () VALUES ()\n");
            case ORACLE, DAMENG -> {
                List<ColumnModel> defaults = keys.stream()
                        .filter(column -> column.column.autoIncrement())
                        .toList();
                if (defaults.isEmpty()) {
                    throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                            "generator.engine.error.empty.insert.default",
                            request.dialect()));
                }
                body.append(" (")
                        .append(defaults.stream().map(column -> sqlIdentifier(
                                        column.column.name(),
                                        request.dialect(),
                                        request.configuration()))
                                .collect(java.util.stream.Collectors.joining(", ")))
                        .append(") VALUES (")
                        .append(defaults.stream().map(ignored -> "DEFAULT")
                                .collect(java.util.stream.Collectors.joining(", ")))
                        .append(")\n");
            }
            case GENERIC, POSTGRESQL, SQL_SERVER, SQLITE, H2 ->
                    body.append(" DEFAULT VALUES\n");
        }
    }

    private static void appendUpdate(
            @NotNull StringBuilder body,
            @NotNull MyBatisGenerationRequest request,
            @NotNull List<ColumnModel> columns,
            @NotNull List<ColumnModel> keys,
            @NotNull String table) {
        if (keys.isEmpty()) {
            return;
        }
        List<ColumnModel> updated = columns.stream()
                .filter(column -> !column.column.primaryKey() && !column.column.autoIncrement())
                .toList();
        if (updated.isEmpty()) {
            return;
        }
        body.append("\n    <update id=\"updateByPrimaryKey\">\n")
                .append("        UPDATE ").append(table).append(" SET ")
                .append(updated.stream().map(column -> sqlIdentifier(
                                column.column.name(), request.dialect(), request.configuration())
                                + " = " + parameter(column, false))
                        .collect(java.util.stream.Collectors.joining(", ")))
                .append(" WHERE ").append(whereClause(keys, request))
                .append("\n    </update>\n");
    }

    private static @NotNull String parameterDeclaration(@NotNull List<ColumnModel> keys) {
        boolean multiple = keys.size() > 1;
        return keys.stream().map(column -> (multiple
                        ? "@Param(\"" + javaString(column.propertyName) + "\") "
                        : "") + column.type.simpleType() + " " + column.propertyName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static @NotNull String serviceParameterDeclaration(
            @NotNull List<ColumnModel> keys) {
        return keys.stream().map(column -> column.type.simpleType() + " " + column.propertyName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static @NotNull String parameterNames(@NotNull List<ColumnModel> keys) {
        return keys.stream().map(column -> column.propertyName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static @NotNull String whereClause(
            @NotNull List<ColumnModel> keys,
            @NotNull MyBatisGenerationRequest request) {
        return keys.stream().map(column -> sqlIdentifier(
                        column.column.name(), request.dialect(), request.configuration())
                        + " = " + parameter(column, false))
                .collect(java.util.stream.Collectors.joining(" AND "));
    }

    private static @NotNull String parameter(
            @NotNull ColumnModel column,
            boolean withEntityPrefix) {
        StringBuilder result = new StringBuilder("#{");
        if (withEntityPrefix) {
            result.append("entity.");
        }
        result.append(column.propertyName)
                .append(",jdbcType=").append(jdbcTypeName(column.column.jdbcType()));
        column.override.typeHandler().ifPresent(handler -> result
                .append(",typeHandler=").append(handler));
        return result.append('}').toString();
    }

    private static void appendPlusFieldAnnotation(
            @NotNull StringBuilder members,
            @NotNull ColumnModel column) {
        if (column.column.primaryKey()) {
            members.append("    @TableId(value = \"")
                    .append(javaString(column.column.name())).append('"');
            if (column.column.autoIncrement()) {
                members.append(", type = IdType.AUTO");
            }
            members.append(")\n");
            return;
        }
        if (!column.column.name().equals(column.propertyName)
                || column.override.typeHandler().isPresent()) {
            members.append("    @TableField(value = \"")
                    .append(javaString(column.column.name())).append('"');
            column.override.typeHandler().ifPresent(handler -> members
                    .append(", typeHandler = ")
                    .append(simpleName(handler)).append(".class"));
            members.append(")\n");
        }
    }

    private static @NotNull List<ColumnModel> columns(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisGenerationConfiguration configuration) {
        List<ColumnModel> result = new ArrayList<>();
        List<MyBatisDatabaseColumn> ordered = table.columns().stream()
                .sorted(Comparator.comparingInt(MyBatisDatabaseColumn::position)
                        .thenComparing(MyBatisDatabaseColumn::name))
                .toList();
        for (MyBatisDatabaseColumn column : ordered) {
            ProgressManager.checkCanceled();
            String normalized = column.name().toLowerCase(Locale.ROOT);
            if (configuration.excludedColumns().contains(normalized)) {
                continue;
            }
            MyBatisGenerationColumnOverride override = configuration.columnOverrides()
                    .getOrDefault(normalized, MyBatisGenerationColumnOverride.empty());
            String property = override.propertyName()
                    .orElseGet(() -> MyBatisGenerationNames.lowerCamel(column.name()));
            result.add(new ColumnModel(
                    column,
                    property,
                    MyBatisJavaTypeMapping.resolve(column, override),
                    override));
        }
        return List.copyOf(result);
    }

    private static void validatePropertyNames(@NotNull List<ColumnModel> columns) {
        Set<String> names = new LinkedHashSet<>();
        for (ColumnModel column : columns) {
            MyBatisGenerationNames.requireJavaIdentifier(column.propertyName);
            if (!names.add(column.propertyName)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.engine.error.property.duplicate", column.propertyName));
            }
        }
    }

    private static @NotNull List<ColumnModel> primaryKeys(
            @NotNull List<ColumnModel> columns) {
        return columns.stream().filter(column -> column.column.primaryKey()).toList();
    }

    private static @NotNull String markerBase(
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String entityName,
            @NotNull String kind) {
        return configuration.basePackage() + "." + entityName + ":" + kind;
    }

    private static @NotNull MyBatisGeneratedArtifact artifact(
            @NotNull MyBatisGenerationArtifactKind kind,
            @NotNull String path,
            @NotNull String content,
            @NotNull String baseId,
            @NotNull String... regions) {
        Set<String> ids = java.util.Arrays.stream(regions)
                .map(region -> baseId + ":" + region)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new MyBatisGeneratedArtifact(kind, path, content, ids);
    }

    private static @NotNull String javaHeader(
            @NotNull String packageName,
            @NotNull Set<String> imports) {
        StringBuilder result = new StringBuilder("package ").append(packageName).append(";\n");
        if (!imports.isEmpty()) {
            result.append('\n');
            imports.forEach(value -> result.append("import ").append(value).append(";\n"));
        }
        return result.toString();
    }

    private static @NotNull String javaPath(
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String packageName,
            @NotNull String fileName) {
        return configuration.javaSourceRoot() + "/"
                + packageName.replace('.', '/') + "/" + fileName;
    }

    private static @NotNull String qualifiedTable(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration) {
        String prefix = table.schema().map(schema -> sqlIdentifier(
                schema, dialect, configuration) + ".").orElse("");
        return prefix + sqlIdentifier(table.name(), dialect, configuration);
    }

    private static @NotNull String sqlIdentifier(
            @NotNull String name,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration) {
        if (!configuration.escapeSqlKeywords()
                || isPlainSqlIdentifier(name) && !SQL_KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) {
            return name;
        }
        return switch (dialect) {
            case MYSQL -> "`" + name.replace("`", "``") + "`";
            case SQL_SERVER -> "[" + name.replace("]", "]]" ) + "]";
            case GENERIC, POSTGRESQL, ORACLE, SQLITE, DAMENG, H2 ->
                    "\"" + name.replace("\"", "\"\"") + "\"";
        };
    }

    private static boolean isPlainSqlIdentifier(@NotNull String name) {
        if (name.isEmpty() || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!Character.isLetterOrDigit(value) && value != '_') {
                return false;
            }
        }
        return true;
    }

    private static @NotNull String jdbcTypeName(int jdbcType) {
        return switch (jdbcType) {
            case Types.ARRAY -> "ARRAY";
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.FLOAT -> "FLOAT";
            case Types.REAL -> "REAL";
            case Types.DOUBLE -> "DOUBLE";
            case Types.NUMERIC -> "NUMERIC";
            case Types.DECIMAL -> "DECIMAL";
            case Types.BIT -> "BIT";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.CHAR -> "CHAR";
            case Types.VARCHAR -> "VARCHAR";
            case Types.LONGVARCHAR -> "LONGVARCHAR";
            case Types.NCHAR -> "NCHAR";
            case Types.NVARCHAR -> "NVARCHAR";
            case Types.LONGNVARCHAR -> "LONGNVARCHAR";
            case Types.NULL -> "NULL";
            case Types.OTHER -> "OTHER";
            case Types.JAVA_OBJECT -> "JAVA_OBJECT";
            case Types.DISTINCT -> "DISTINCT";
            case Types.STRUCT -> "STRUCT";
            case Types.REF -> "REF";
            case Types.DATALINK -> "DATALINK";
            case Types.ROWID -> "ROWID";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIME_WITH_TIMEZONE -> "TIME_WITH_TIMEZONE";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_WITH_TIMEZONE";
            case Types.BINARY -> "BINARY";
            case Types.VARBINARY -> "VARBINARY";
            case Types.LONGVARBINARY -> "LONGVARBINARY";
            case Types.BLOB -> "BLOB";
            case Types.CLOB -> "CLOB";
            case Types.NCLOB -> "NCLOB";
            case Types.SQLXML -> "SQLXML";
            case Types.REF_CURSOR -> "CURSOR";
            default -> "OTHER";
        };
    }

    private static @NotNull Collection<String> referencedEntityTypes(
            @NotNull List<ColumnModel> columns,
            boolean plus) {
        List<String> types = new ArrayList<>();
        columns.stream().map(column -> column.type.canonicalType()).forEach(types::add);
        if (plus) {
            types.add("com.baomidou.mybatisplus.annotation.TableField");
            types.add("com.baomidou.mybatisplus.annotation.TableId");
            types.add("com.baomidou.mybatisplus.annotation.TableName");
            if (columns.stream().anyMatch(column -> column.column.autoIncrement()
                    && column.column.primaryKey())) {
                types.add("com.baomidou.mybatisplus.annotation.IdType");
            }
            columns.forEach(column -> column.override.typeHandler().ifPresent(types::add));
        }
        return types;
    }

    private static @NotNull Collection<String> mapperTypes(
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String entityName,
            @NotNull List<ColumnModel> keys,
            boolean plus) {
        List<String> types = new ArrayList<>();
        types.add(configuration.basePackage() + ".entity." + entityName);
        keys.stream().map(column -> column.type.canonicalType()).forEach(types::add);
        types.add("java.util.List");
        if (plus) {
            types.add("com.baomidou.mybatisplus.core.mapper.BaseMapper");
        } else if (keys.size() > 1) {
            types.add("org.apache.ibatis.annotations.Param");
        }
        return types;
    }

    private static @NotNull Collection<String> serviceTypes(
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String entityName,
            @NotNull String mapperName,
            @NotNull List<ColumnModel> keys,
            boolean plus) {
        List<String> types = new ArrayList<>();
        types.add(configuration.basePackage() + ".entity." + entityName);
        types.add(configuration.basePackage() + ".mapper." + mapperName);
        types.add("java.util.List");
        keys.stream().map(column -> column.type.canonicalType()).forEach(types::add);
        if (plus) {
            types.add("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl");
        }
        return types;
    }

    private static void validateTypeNames(
            @NotNull String declaredName,
            @NotNull Collection<String> canonicalTypes) {
        Map<String, String> names = new HashMap<>();
        for (String canonicalType : canonicalTypes) {
            String component = canonicalType.endsWith("[]")
                    ? canonicalType.substring(0, canonicalType.length() - 2)
                    : canonicalType;
            String shortName = simpleName(component);
            if (declaredName.equals(shortName)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.engine.error.type.declared.conflict", declaredName));
            }
            String previous = names.putIfAbsent(shortName, component);
            if (previous != null && !previous.equals(component)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.engine.error.type.reference.conflict",
                        previous, component));
            }
        }
    }

    private static @NotNull String stripPrefix(
            @NotNull String tableName,
            @NotNull String prefix) {
        return !prefix.isEmpty() && tableName.regionMatches(true, 0, prefix, 0, prefix.length())
                && tableName.length() > prefix.length()
                ? tableName.substring(prefix.length())
                : tableName;
    }

    private static @NotNull String region(
            @NotNull MyBatisGeneratedRegion.Style style,
            @NotNull String id,
            @NotNull String body) {
        return MyBatisGeneratedRegion.render(style, id, body);
    }

    private static @NotNull String javaDoc(@NotNull String value) {
        String safe = value.replace("*/", "* /").replace('\r', ' ').replace('\n', ' ');
        return "/**\n * " + safe + "\n */\n";
    }

    private static @NotNull String xmlComment(@NotNull String value) {
        return value.replace("--", "- -").replace('\r', ' ').replace('\n', ' ');
    }

    private static @NotNull String xmlAttribute(@NotNull String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static @NotNull String javaString(@NotNull String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static @NotNull String simpleName(@NotNull String canonicalName) {
        int separator = canonicalName.lastIndexOf('.');
        return separator < 0 ? canonicalName : canonicalName.substring(separator + 1);
    }

    private static @NotNull String indent(@NotNull String value, int level) {
        String prefix = "    ".repeat(level);
        return value.lines().map(line -> line.isEmpty() ? line : prefix + line)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
    }

    private record ColumnModel(
            MyBatisDatabaseColumn column,
            String propertyName,
            MyBatisJavaTypeMapping type,
            MyBatisGenerationColumnOverride override) {
    }
}
