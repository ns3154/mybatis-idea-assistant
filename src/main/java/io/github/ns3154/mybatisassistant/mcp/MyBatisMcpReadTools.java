package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.openapi.vfs.VfsUtilCore;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataResult;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceConfig;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceManager;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperMethodModel;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 首批有界只读 MCP 工具；所有 PSI 查询都在读动作内完成。
 */
final class MyBatisMcpReadTools {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_DATA_SOURCES = 64;
    private static final int MAX_COLUMNS_PER_TABLE = 500;
    private static final int MAX_TOTAL_COLUMNS = 2000;
    private static final int MAX_TEXT_LENGTH = 512;

    private MyBatisMcpReadTools() {
    }

    static @NotNull List<MyBatisMcpTool> all() {
        return List.of(
                new DatabaseDataSourcesTool(),
                new DatabaseSchemaTool(),
                new MapperListTool(),
                new ParameterDescribeTool(),
                new ReferenceFindTool(),
                new StatementListTool());
    }

    private abstract static class ReadTool implements MyBatisMcpTool {
        @Override
        public final boolean readOnly() {
            return true;
        }

        @Override
        public final @NotNull JsonObject inputSchema() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties());
            JsonArray required = new JsonArray();
            requiredProperties().forEach(required::add);
            schema.add("required", required);
            schema.addProperty("additionalProperties", false);
            return schema;
        }

        protected @NotNull JsonObject properties() {
            return new JsonObject();
        }

        protected @NotNull java.util.Set<String> requiredProperties() {
            return java.util.Set.of();
        }
    }

    private static final class MapperListTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "mapper.list";
        }

        @Override
        public @NotNull String description() {
            return "按精确全限定名读取当前项目 Mapper 模型";
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("qualifiedName", stringSchema("Mapper 接口全限定名"));
            properties.add("limit", integerSchema("最大返回方法数量", 1, MAX_LIMIT));
            return properties;
        }

        @Override
        protected @NotNull java.util.Set<String> requiredProperties() {
            return java.util.Set.of("qualifiedName");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String qualifiedName = requiredString(arguments, "qualifiedName");
            int limit = limit(arguments);
            return read(project, () -> mapper(project, qualifiedName, true, limit));
        }
    }

    private static final class StatementListTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "statement.list";
        }

        @Override
        public @NotNull String description() {
            return "读取精确 Mapper 的自定义方法与 statement 来源";
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("qualifiedName", stringSchema("Mapper 接口全限定名"));
            properties.add("limit", integerSchema("最大返回数量", 1, MAX_LIMIT));
            return properties;
        }

        @Override
        protected @NotNull java.util.Set<String> requiredProperties() {
            return java.util.Set.of("qualifiedName");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String qualifiedName = requiredString(arguments, "qualifiedName");
            int limit = limit(arguments);
            return read(project, () -> {
                JsonObject mapper = mapper(project, qualifiedName, false, limit);
                JsonArray methods = mapper.remove("methods").getAsJsonArray();
                JsonObject result = new JsonObject();
                result.addProperty("qualifiedName", qualifiedName);
                result.addProperty("total", mapper.get("total").getAsInt());
                result.addProperty("truncated", mapper.get("truncated").getAsBoolean());
                result.add("statements", methods);
                return result;
            });
        }
    }

    private static final class ParameterDescribeTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "parameter.describe";
        }

        @Override
        public @NotNull String description() {
            return "读取精确 Mapper 方法签名及参数模型";
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("qualifiedName", stringSchema("Mapper 接口全限定名"));
            properties.add("signature", stringSchema("方法稳定签名，例如 findById(java.lang.Long)"));
            return properties;
        }

        @Override
        protected @NotNull java.util.Set<String> requiredProperties() {
            return java.util.Set.of("qualifiedName", "signature");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String qualifiedName = requiredString(arguments, "qualifiedName");
            String signature = requiredString(arguments, "signature");
            return read(project, () -> {
                MyBatisMapperMethodModel method = model(project, qualifiedName).methods().stream()
                        .filter(candidate -> signature.equals(candidate.stableSignature()))
                        .findFirst()
                        .orElseThrow(() -> new MyBatisMcpToolException(
                                "未找到精确 Mapper 方法：" + qualifiedName + '#' + signature));
                return methodJson(method);
            });
        }
    }

    private static final class DatabaseDataSourcesTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "database.data_sources";
        }

        @Override
        public @NotNull String description() {
            return "读取 Community JDBC 非敏感数据源摘要";
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            JsonArray sources = new JsonArray();
            int total = 0;
            for (MyBatisJdbcDataSourceConfig source : MyBatisJdbcDataSourceManager
                    .getInstance(project).dataSources()) {
                ProgressManager.checkCanceled();
                total++;
                if (sources.size() >= MAX_DATA_SOURCES) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("id", boundedText(source.id()));
                item.addProperty("displayName", boundedText(source.displayName()));
                item.addProperty("dialect", source.dialect().name());
                item.addProperty("enabled", source.enabled());
                item.addProperty("passwordRequired", source.passwordRequired());
                item.addProperty("catalog", boundedText(source.catalog().orElse("")));
                item.addProperty("schema", boundedText(source.schema().orElse("")));
                sources.add(item);
            }
            JsonObject result = new JsonObject();
            result.addProperty("total", total);
            result.addProperty("truncated", total > sources.size());
            result.add("dataSources", sources);
            return result;
        }
    }

    private static final class DatabaseSchemaTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "database.schema";
        }

        @Override
        public @NotNull String description() {
            return "读取已加载数据库快照，不触发连接";
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("dataSourceId", stringSchema("数据源 ID；留空返回全部已加载快照"));
            properties.add("limit", integerSchema("最大返回表数量", 1, MAX_LIMIT));
            return properties;
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            MyBatisDatabaseMetadataResult.Loaded loaded = MyBatisDatabaseMetadataService
                    .getInstance(project).latest()
                    .orElseThrow(() -> new MyBatisMcpToolException("数据库元数据尚未加载"));
            String requestedId = optionalString(arguments, "dataSourceId");
            int limit = limit(arguments);
            JsonArray snapshots = new JsonArray();
            int emitted = 0;
            int total = 0;
            int matchingSnapshots = 0;
            long matchingColumns = 0;
            long emittedColumns = 0;
            for (MyBatisDatabaseSnapshot snapshot : loaded.snapshots()) {
                ProgressManager.checkCanceled();
                if (!requestedId.isEmpty() && !requestedId.equals(snapshot.dataSourceId())) {
                    continue;
                }
                matchingSnapshots++;
                matchingColumns += snapshot.tables().stream()
                        .mapToLong(table -> table.columns().size())
                        .sum();
                if (snapshots.size() >= MAX_DATA_SOURCES) {
                    total += snapshot.tables().size();
                    continue;
                }
                JsonObject snapshotJson = new JsonObject();
                snapshotJson.addProperty("dataSourceId", boundedText(snapshot.dataSourceId()));
                snapshotJson.addProperty("displayName", boundedText(snapshot.displayName()));
                snapshotJson.addProperty("dialect", snapshot.dialect().name());
                snapshotJson.addProperty("freshness", snapshot.freshness().name());
                JsonArray tables = new JsonArray();
                for (MyBatisDatabaseTable table : snapshot.tables()) {
                    total++;
                    if (emitted >= limit) {
                        continue;
                    }
                    int columnLimit = (int) Math.min(
                            MAX_COLUMNS_PER_TABLE,
                            Math.max(0L, MAX_TOTAL_COLUMNS - emittedColumns));
                    tables.add(tableJson(table, columnLimit));
                    emittedColumns += Math.min(columnLimit, table.columns().size());
                    emitted++;
                }
                snapshotJson.add("tables", tables);
                snapshots.add(snapshotJson);
            }
            JsonObject result = new JsonObject();
            result.addProperty("totalTables", total);
            result.addProperty("truncated", total > emitted);
            result.addProperty("snapshotsTruncated", matchingSnapshots > snapshots.size());
            result.addProperty("columnsTruncated", matchingColumns > emittedColumns);
            result.add("snapshots", snapshots);
            return result;
        }
    }

    private static final class ReferenceFindTool extends ReadTool {
        @Override
        public @NotNull String name() {
            return "reference.find";
        }

        @Override
        public @NotNull String description() {
            return "读取精确 Mapper 方法的静态声明位置，不执行全项目文本扫描";
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("qualifiedName", stringSchema("Mapper 接口全限定名"));
            properties.add("signature", stringSchema("可选方法稳定签名"));
            properties.add("limit", integerSchema("最大返回引用数量", 1, MAX_LIMIT));
            return properties;
        }

        @Override
        protected @NotNull java.util.Set<String> requiredProperties() {
            return java.util.Set.of("qualifiedName");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String qualifiedName = requiredString(arguments, "qualifiedName");
            String signature = optionalString(arguments, "signature");
            int limit = limit(arguments);
            return read(project, () -> {
                PsiClass mapperClass = exactClass(project, qualifiedName);
                PsiElement target = mapperClass;
                if (!signature.isEmpty()) {
                    MyBatisMapperMethodModel model = model(project, qualifiedName).methods().stream()
                            .filter(candidate -> signature.equals(candidate.stableSignature()))
                            .findFirst()
                            .orElseThrow(() -> new MyBatisMcpToolException(
                                    "未找到精确 Mapper 方法：" + signature));
                    target = java.util.Arrays.stream(mapperClass.findMethodsByName(
                                    model.name(), true))
                            .filter(method -> signature.equals(stableSignature(method)))
                            .findFirst()
                            .orElseThrow(() -> new MyBatisMcpToolException(
                                    "未找到精确 Mapper 方法声明：" + signature));
                }
                JsonArray declarations = new JsonArray();
                JsonObject declaration = location(project, target);
                declaration.addProperty("kind", signature.isEmpty() ? "MAPPER" : "METHOD");
                declarations.add(declaration);
                JsonArray references = new JsonArray();
                boolean completed = ReferencesSearch.search(
                                target,
                                GlobalSearchScope.projectScope(project))
                        .forEach(reference -> {
                            ProgressManager.checkCanceled();
                            if (references.size() >= limit) {
                                return false;
                            }
                            JsonObject referenceLocation = optionalLocation(
                                    project, reference.getElement());
                            if (referenceLocation == null) {
                                return true;
                            }
                            referenceLocation.addProperty(
                                    "canonicalText", boundedText(reference.getCanonicalText()));
                            references.add(referenceLocation);
                            return true;
                        });
                JsonObject result = new JsonObject();
                result.add("declarations", declarations);
                result.add("references", references);
                result.addProperty("truncated", !completed);
                return result;
            });
        }
    }

    private static @NotNull JsonObject mapper(
            @NotNull Project project,
            @NotNull String qualifiedName,
            boolean includeEvidence,
            int limit) {
        var model = model(project, qualifiedName);
        JsonObject result = new JsonObject();
        result.addProperty("qualifiedName", model.qualifiedName());
        if (includeEvidence) {
            JsonArray evidence = new JsonArray();
            model.evidence().stream().limit(DEFAULT_LIMIT).forEach(item -> {
                JsonObject json = new JsonObject();
                json.addProperty("kind", item.kind().name());
                json.addProperty("detail", boundedText(item.detail()));
                evidence.add(json);
            });
            result.add("evidence", evidence);
            result.addProperty("evidenceTruncated", model.evidence().size() > evidence.size());
        }
        JsonArray methods = new JsonArray();
        model.methods().stream()
                .sorted(java.util.Comparator.comparing(MyBatisMapperMethodModel::stableSignature))
                .limit(limit)
                .forEach(method -> methods.add(methodJson(method)));
        result.addProperty("total", model.methods().size());
        result.addProperty("truncated", model.methods().size() > methods.size());
        result.add("methods", methods);
        return result;
    }

    private static @NotNull io.github.ns3154.mybatisassistant.model.MyBatisMapperModel model(
            @NotNull Project project,
            @NotNull String qualifiedName) {
        if (DumbService.isDumb(project)) {
            throw new MyBatisMcpToolException("索引尚未就绪");
        }
        PsiClass mapper = exactClass(project, qualifiedName);
        MyBatisMapperModelResolution resolution = MyBatisMapperModelResolver.resolve(mapper);
        if (resolution instanceof MyBatisMapperModelResolution.Found found) {
            return found.model();
        }
        throw new MyBatisMcpToolException("未找到确定的 Mapper 模型：" + qualifiedName);
    }

    private static @NotNull PsiClass exactClass(
            @NotNull Project project,
            @NotNull String qualifiedName) {
        PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(
                qualifiedName,
                GlobalSearchScope.projectScope(project));
        List<PsiClass> exact = java.util.Arrays.stream(classes)
                .filter(candidate -> qualifiedName.equals(candidate.getQualifiedName()))
                .toList();
        if (exact.size() != 1) {
            throw new MyBatisMcpToolException(
                    exact.isEmpty() ? "未找到类型：" + qualifiedName : "类型目标不唯一：" + qualifiedName);
        }
        return exact.getFirst();
    }

    private static @NotNull JsonObject methodJson(@NotNull MyBatisMapperMethodModel method) {
        JsonObject json = new JsonObject();
        json.addProperty("name", method.name());
        json.addProperty("signature", boundedText(method.stableSignature()));
        json.addProperty("declaringType", boundedText(method.declaringType()));
        json.addProperty("returnType", boundedText(method.returnType()));
        json.addProperty("statementSource", method.statementSource().name());
        json.addProperty("inherited", method.inherited());
        JsonArray parameters = new JsonArray();
        for (MyBatisParameterModel parameter : method.parameters()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", parameter.name());
            item.addProperty("type", boundedText(parameter.canonicalType()));
            item.addProperty("explicit", parameter.explicitlyNamed());
            parameters.add(item);
        }
        json.add("parameters", parameters);
        return json;
    }

    private static @NotNull JsonObject tableJson(
            @NotNull MyBatisDatabaseTable table,
            int columnLimit) {
        JsonObject json = new JsonObject();
        json.addProperty("catalog", boundedText(table.catalog().orElse("")));
        json.addProperty("schema", boundedText(table.schema().orElse("")));
        json.addProperty("name", boundedText(table.name()));
        json.addProperty("comment", boundedText(table.comment().orElse("")));
        JsonArray columns = new JsonArray();
        for (MyBatisDatabaseColumn column : table.columns().stream()
                .limit(columnLimit).toList()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", boundedText(column.name()));
            item.addProperty("type", boundedText(column.typeName()));
            item.addProperty("jdbcType", column.jdbcType());
            item.addProperty("nullable", column.nullable());
            item.addProperty("primaryKey", column.primaryKey());
            item.addProperty("foreignKey", column.foreignKey());
            item.addProperty("autoIncrement", column.autoIncrement());
            item.addProperty("comment", boundedText(column.comment().orElse("")));
            columns.add(item);
        }
        json.addProperty("totalColumns", table.columns().size());
        json.addProperty("columnsTruncated", table.columns().size() > columns.size());
        json.add("columns", columns);
        return json;
    }

    private static @NotNull String boundedText(@NotNull String value) {
        return value.length() <= MAX_TEXT_LENGTH
                ? value
                : value.substring(0, MAX_TEXT_LENGTH);
    }

    private static @NotNull JsonObject location(
            @NotNull Project project,
            @NotNull PsiElement element) {
        JsonObject location = optionalLocation(project, element);
        if (location == null) {
            throw new MyBatisMcpToolException("声明不在当前项目内容根内");
        }
        return location;
    }

    private static @Nullable JsonObject optionalLocation(
            @NotNull Project project,
            @NotNull PsiElement element) {
        if (element.getContainingFile() == null
                || element.getContainingFile().getVirtualFile() == null) {
            return null;
        }
        var file = element.getContainingFile().getVirtualFile();
        var contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        if (contentRoot == null) {
            return null;
        }
        String relativePath = VfsUtilCore.getRelativePath(
                file,
                contentRoot);
        if (relativePath == null || relativePath.length() > MAX_TEXT_LENGTH * 4) {
            return null;
        }
        JsonObject location = new JsonObject();
        location.addProperty("projectRelativePath", relativePath);
        location.addProperty("offset", element.getTextOffset());
        return location;
    }

    private static @NotNull String stableSignature(@NotNull com.intellij.psi.PsiMethod method) {
        String parameters = java.util.Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> parameter.getType().getCanonicalText())
                .collect(java.util.stream.Collectors.joining(","));
        return method.getName() + '(' + parameters + ')';
    }

    private static @NotNull JsonObject read(
            @NotNull Project project,
            @NotNull java.util.function.Supplier<JsonObject> supplier) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                throw new MyBatisMcpToolException("项目已关闭");
            }
            return supplier.get();
        });
    }

    private static @NotNull JsonObject stringSchema(@NotNull String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        schema.addProperty("maxLength", 512);
        return schema;
    }

    private static @NotNull JsonObject integerSchema(
            @NotNull String description,
            int minimum,
            int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static @NotNull String requiredString(
            @NotNull JsonObject arguments,
            @NotNull String name) {
        String value = optionalString(arguments, name);
        if (value.isEmpty()) {
            throw new MyBatisMcpToolException("缺少参数：" + name);
        }
        return value;
    }

    private static @NotNull String optionalString(
            @NotNull JsonObject arguments,
            @NotNull String name) {
        var value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        String normalized = value.getAsString().trim();
        if (normalized.length() > 512) {
            throw new MyBatisMcpToolException("参数超过 512 字符上限：" + name);
        }
        return normalized;
    }

    private static int limit(@NotNull JsonObject arguments) {
        var value = arguments.get("limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = value.getAsInt();
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new MyBatisMcpToolException("limit 必须在 1～500 之间");
            }
            return limit;
        } catch (NumberFormatException | ClassCastException failure) {
            throw new MyBatisMcpToolException("limit 必须为整数");
        }
    }
}
