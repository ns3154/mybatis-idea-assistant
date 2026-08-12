package io.github.ns3154.mybatisassistant.methodsql;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 只接受显式外键到主键关系，不从名称或相似度猜测 Join。
 */
public final class MyBatisJoinSqlGenerator {
    private MyBatisJoinSqlGenerator() {
    }

    public static @NotNull MyBatisJoinGeneration generate(
            @NotNull MyBatisJoinGenerationRequest request) {
        requireAlias(request.baseAlias(), "基表");
        Map<String, MyBatisMethodSchema> schemas = new LinkedHashMap<>();
        schemas.put(request.baseAlias(), request.baseSchema());
        for (MyBatisJoinSpec join : request.joins()) {
            ProgressManager.checkCanceled();
            validateDialectJoin(request.dialect(), join.type());
            requireAlias(join.targetAlias(), "Join 目标");
            if (schemas.containsKey(join.targetAlias())) {
                throw new IllegalArgumentException("Join 表别名重复：" + join.targetAlias());
            }
            for (MyBatisJoinRelation relation : join.relations()) {
                MyBatisMethodSchema source = schemas.get(relation.sourceAlias());
                if (source == null) {
                    throw new IllegalArgumentException(
                            "Join 来源别名尚未注册：" + relation.sourceAlias());
                }
                requireField(source, relation.sourceField(), relation.sourceAlias());
                requireField(join.targetSchema(), relation.targetField(), join.targetAlias());
                if (!isForeignKeyToPrimaryKey(
                        relation.sourceField(), relation.targetField())) {
                    throw new IllegalArgumentException(
                            "Join 关系缺少外键到主键证据：" + relation.sourceAlias()
                                    + "." + relation.sourceField().propertyName() + " -> "
                                    + join.targetAlias() + "."
                                    + relation.targetField().propertyName());
                }
            }
            schemas.put(join.targetAlias(), join.targetSchema());
        }
        List<String> labels = new ArrayList<>();
        Set<String> uniqueLabels = new LinkedHashSet<>();
        for (MyBatisJoinSelection selection : request.selections()) {
            ProgressManager.checkCanceled();
            requireAlias(selection.tableAlias(), "输出表");
            MyBatisMethodSchema schema = schemas.get(selection.tableAlias());
            if (schema == null) {
                throw new IllegalArgumentException(
                        "Join 输出引用未知表别名：" + selection.tableAlias());
            }
            requireField(schema, selection.field(), selection.tableAlias());
            selection.outputAlias().ifPresent(alias -> requireAlias(alias, "输出列"));
            String label = selection.outputAlias().orElse(selection.field().columnName());
            if (!uniqueLabels.add(label)) {
                throw new IllegalArgumentException(
                        "Join 输出列标签重复，请显式设置别名：" + label);
            }
            labels.add(label);
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(request.selections().stream()
                        .map(selection -> selection(selection, request))
                        .collect(Collectors.joining(", ")))
                .append(" FROM ")
                .append(identifier(request.baseSchema().tableName(), request.dialect(),
                        request.escapeIdentifiers()))
                .append(' ')
                .append(identifier(request.baseAlias(), request.dialect(),
                        request.escapeIdentifiers()));
        for (MyBatisJoinSpec join : request.joins()) {
            sql.append(' ').append(joinKeyword(join.type())).append(' ')
                    .append(identifier(join.targetSchema().tableName(), request.dialect(),
                            request.escapeIdentifiers()))
                    .append(' ')
                    .append(identifier(join.targetAlias(), request.dialect(),
                            request.escapeIdentifiers()))
                    .append(" ON ")
                    .append(join.relations().stream()
                            .map(relation -> relation(relation, join, request))
                            .collect(Collectors.joining(" AND ")));
        }
        return new MyBatisJoinGeneration(sql.toString(), labels);
    }

    private static @NotNull String selection(
            @NotNull MyBatisJoinSelection selection,
            @NotNull MyBatisJoinGenerationRequest request) {
        String result = qualified(
                selection.tableAlias(), selection.field().columnName(), request);
        if (selection.outputAlias().isPresent()) {
            result += " AS " + identifier(
                    selection.outputAlias().orElseThrow(),
                    request.dialect(), request.escapeIdentifiers());
        }
        return result;
    }

    private static @NotNull String relation(
            @NotNull MyBatisJoinRelation relation,
            @NotNull MyBatisJoinSpec join,
            @NotNull MyBatisJoinGenerationRequest request) {
        return qualified(relation.sourceAlias(), relation.sourceField().columnName(), request)
                + " = " + qualified(
                        join.targetAlias(), relation.targetField().columnName(), request);
    }

    private static @NotNull String qualified(
            @NotNull String tableAlias,
            @NotNull String column,
            @NotNull MyBatisJoinGenerationRequest request) {
        return identifier(tableAlias, request.dialect(), request.escapeIdentifiers())
                + "." + identifier(column, request.dialect(), request.escapeIdentifiers());
    }

    private static void requireField(
            @NotNull MyBatisMethodSchema schema,
            @NotNull MyBatisMethodField field,
            @NotNull String alias) {
        if (schema.fields().stream().noneMatch(candidate -> candidate == field)) {
            throw new IllegalArgumentException(
                    "字段不属于 Join 表 " + alias + "：" + field.propertyName());
        }
    }

    private static boolean isForeignKeyToPrimaryKey(
            @NotNull MyBatisMethodField first,
            @NotNull MyBatisMethodField second) {
        return first.foreignKey() && second.primaryKey()
                || second.foreignKey() && first.primaryKey();
    }

    private static void requireAlias(@NotNull String value, @NotNull String role) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(role + "别名不是安全标识符：" + value);
        }
    }

    private static @NotNull String joinKeyword(@NotNull MyBatisJoinType type) {
        return switch (type) {
            case INNER -> "INNER JOIN";
            case LEFT -> "LEFT JOIN";
            case RIGHT -> "RIGHT JOIN";
            case FULL -> "FULL JOIN";
        };
    }

    private static void validateDialectJoin(
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisJoinType type) {
        if (dialect == MyBatisSqlDialect.MYSQL && type == MyBatisJoinType.FULL) {
            throw new IllegalArgumentException("MySQL 不支持 FULL JOIN");
        }
        if (dialect == MyBatisSqlDialect.SQLITE
                && (type == MyBatisJoinType.RIGHT || type == MyBatisJoinType.FULL)) {
            throw new IllegalArgumentException(
                    "SQLite 版本差异可能不支持 RIGHT/FULL JOIN，请改用 LEFT/INNER JOIN");
        }
    }

    private static @NotNull String identifier(
            @NotNull String value,
            @NotNull MyBatisSqlDialect dialect,
            boolean escape) {
        if (!escape) {
            return value;
        }
        return switch (dialect) {
            case MYSQL -> "`" + value.replace("`", "``") + "`";
            case SQL_SERVER -> "[" + value.replace("]", "]]" ) + "]";
            case GENERIC, POSTGRESQL, ORACLE, SQLITE, DAMENG, H2 ->
                    "\"" + value.replace("\"", "\"\"") + "\"";
        };
    }
}
