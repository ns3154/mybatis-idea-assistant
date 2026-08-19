package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 外键列明确指向的数据库对象身份。
 */
public record MyBatisForeignKeyReference(
        @NotNull Optional<String> catalog,
        @NotNull Optional<String> schema,
        @NotNull String table,
        @NotNull String column,
        @NotNull Optional<String> constraintIdentity,
        int ordinal,
        int columnCount) {
    public MyBatisForeignKeyReference {
        catalog = catalog.map(String::trim).filter(value -> !value.isEmpty());
        schema = schema.map(String::trim).filter(value -> !value.isEmpty());
        table = table.trim();
        column = column.trim();
        constraintIdentity = constraintIdentity.map(String::trim)
                .filter(value -> !value.isEmpty());
        if (table.isEmpty() || column.isEmpty() || ordinal < 1 || columnCount < 1
                || ordinal > columnCount) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.foreign.key.reference.incomplete"));
        }
    }

    /**
     * 兼容只表达单列外键目标的调用方。
     */
    public MyBatisForeignKeyReference(
            @NotNull Optional<String> catalog,
            @NotNull Optional<String> schema,
            @NotNull String table,
            @NotNull String column) {
        this(catalog, schema, table, column, Optional.empty(), 1, 1);
    }

    /**
     * 只有目录、模式、表名和列名全部相同时才视为同一个目标。
     */
    public boolean matches(
            @NotNull Optional<String> targetCatalog,
            @NotNull Optional<String> targetSchema,
            @NotNull String targetTable,
            @NotNull String targetColumn) {
        return columnCount == 1
                && ordinal == 1
                && catalog.equals(targetCatalog)
                && schema.equals(targetSchema)
                && table.equals(targetTable)
                && column.equals(targetColumn);
    }
}
