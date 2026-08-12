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
        requireAlias(request.baseAlias(), MyBatisMethodSqlMessages.message(
                "methodsql.role.join.base.table"));
        Map<String, MyBatisMethodSchema> schemas = new LinkedHashMap<>();
        schemas.put(request.baseAlias(), request.baseSchema());
        for (MyBatisJoinSpec join : request.joins()) {
            ProgressManager.checkCanceled();
            validateDialectJoin(request.dialect(), join.type());
            requireAlias(join.targetAlias(), MyBatisMethodSqlMessages.message(
                    "methodsql.role.join.target"));
            if (schemas.containsKey(join.targetAlias())) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.join.error.alias.duplicate", join.targetAlias()));
            }
            for (MyBatisJoinRelation relation : join.relations()) {
                MyBatisMethodSchema source = schemas.get(relation.sourceAlias());
                if (source == null) {
                    throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                            "methodsql.join.error.source.unregistered",
                            relation.sourceAlias()));
                }
                requireField(source, relation.sourceField(), relation.sourceAlias());
                requireField(join.targetSchema(), relation.targetField(), join.targetAlias());
                if (!isForeignKeyToPrimaryKey(
                        relation.sourceField(), relation.targetField())) {
                    throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                            "methodsql.join.error.relation.unverified",
                            relation.sourceAlias(), relation.sourceField().propertyName(),
                            join.targetAlias(), relation.targetField().propertyName()));
                }
            }
            schemas.put(join.targetAlias(), join.targetSchema());
        }
        List<String> labels = new ArrayList<>();
        Set<String> uniqueLabels = new LinkedHashSet<>();
        for (MyBatisJoinSelection selection : request.selections()) {
            ProgressManager.checkCanceled();
            requireAlias(selection.tableAlias(), MyBatisMethodSqlMessages.message(
                    "methodsql.role.join.output.table"));
            MyBatisMethodSchema schema = schemas.get(selection.tableAlias());
            if (schema == null) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.join.error.output.alias.unknown", selection.tableAlias()));
            }
            requireField(schema, selection.field(), selection.tableAlias());
            selection.outputAlias().ifPresent(alias -> requireAlias(alias,
                    MyBatisMethodSqlMessages.message("methodsql.role.join.output.column")));
            String label = selection.outputAlias().orElse(selection.field().columnName());
            if (!uniqueLabels.add(label)) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.join.error.output.label.duplicate", label));
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
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.join.error.field.not.member", alias, field.propertyName()));
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
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.join.error.alias.invalid", role, value));
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
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.join.error.mysql.full"));
        }
        if (dialect == MyBatisSqlDialect.SQLITE
                && (type == MyBatisJoinType.RIGHT || type == MyBatisJoinType.FULL)) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.join.error.sqlite.right.full"));
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
