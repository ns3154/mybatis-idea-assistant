package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 与具体数据库插件解耦的列元数据。
 */
public record MyBatisDatabaseColumn(
        @NotNull String name,
        @NotNull String typeName,
        int jdbcType,
        boolean nullable,
        boolean primaryKey,
        boolean foreignKey,
        boolean autoIncrement,
        @NotNull Optional<String> comment,
        int position) {
    public MyBatisDatabaseColumn {
        if (name.isBlank()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.column.name.empty"));
        }
        if (typeName.isBlank()) {
            typeName = "UNKNOWN";
        }
        if (position < 0) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.column.position.negative"));
        }
        comment = comment.filter(value -> !value.isBlank());
    }

    public MyBatisDatabaseColumn(
            @NotNull String name,
            @NotNull String typeName,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            int position) {
        this(name, typeName, jdbcType, nullable, primaryKey, foreignKey,
                false, Optional.empty(), position);
    }
}
