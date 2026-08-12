package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 按数据库可执行的命名空间层级逐段渲染表标识符，禁止把限定名当成单个标识符引用。
 */
final class MyBatisSqlIdentifierRenderer {
    private static final java.util.Set<String> SQL_KEYWORDS = java.util.Set.of(
            "select", "from", "where", "join", "left", "right", "inner", "outer",
            "group", "order", "having", "limit", "offset", "insert", "update",
            "delete", "values", "set", "and", "or", "on", "as", "distinct",
            "case", "when", "then", "else", "end", "null", "is", "in", "exists",
            "like", "table", "user", "key", "primary", "foreign", "index", "constraint");
    private MyBatisSqlIdentifierRenderer() {
    }

    static @NotNull String qualifiedTable(
            @NotNull MyBatisMethodSchema schema,
            @NotNull MyBatisSqlDialect dialect,
            boolean escape) {
        List<String> parts = new ArrayList<>(3);
        switch (dialect) {
            case GENERIC, SQL_SERVER, H2 -> {
                schema.catalog().ifPresent(parts::add);
                schema.schema().ifPresent(parts::add);
            }
            case MYSQL, SQLITE -> schema.catalog()
                    .or(() -> schema.schema())
                    .ifPresent(parts::add);
            case POSTGRESQL, ORACLE, DAMENG -> schema.schema().ifPresent(parts::add);
        }
        parts.add(schema.tableName());
        return parts.stream()
                .map(part -> identifier(part, dialect, escape))
                .collect(java.util.stream.Collectors.joining("."));
    }

    static @NotNull String identifier(
            @NotNull String value,
            @NotNull MyBatisSqlDialect dialect,
            boolean escape) {
        requireNoMyBatisToken(value);
        if (!escape) {
            requirePlain(value);
            return value;
        }
        return switch (dialect) {
            case MYSQL -> "`" + value.replace("`", "``") + "`";
            case SQL_SERVER -> "[" + value.replace("]", "]]" ) + "]";
            case GENERIC, POSTGRESQL, ORACLE, SQLITE, DAMENG, H2 ->
                    "\"" + value.replace("\"", "\"\"") + "\"";
        };
    }

    static boolean isPlainNonKeyword(@NotNull String value) {
        return !containsMyBatisToken(value)
                && value.matches("[A-Za-z_][A-Za-z0-9_$]*")
                && !SQL_KEYWORDS.contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static void requireNoMyBatisToken(@NotNull String value) {
        if (containsMyBatisToken(value)) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.identifier.mybatis.token", value));
        }
    }

    private static boolean containsMyBatisToken(@NotNull String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static @NotNull String requirePlain(@NotNull String value) {
        if (!isPlainNonKeyword(value)) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.identifier.unescaped", value));
        }
        return value;
    }
}
