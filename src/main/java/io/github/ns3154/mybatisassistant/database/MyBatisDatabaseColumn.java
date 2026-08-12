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
        boolean generated,
        @NotNull Optional<String> comment,
        int position,
        @NotNull Optional<MyBatisForeignKeyReference> foreignKeyReference) {
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
        if (!foreignKey && foreignKeyReference.isPresent()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.foreign.key.reference.without.flag"));
        }
    }

    /**
     * 保留尚未携带外键目标身份的完整列模型调用方；目标身份默认未知。
     */
    public MyBatisDatabaseColumn(
            @NotNull String name,
            @NotNull String typeName,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement,
            boolean generated,
            @NotNull Optional<String> comment,
            int position) {
        this(name, typeName, jdbcType, nullable, primaryKey, foreignKey,
                autoIncrement, generated, comment, position, Optional.empty());
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
                false, false, Optional.empty(), position, Optional.empty());
    }

    public MyBatisDatabaseColumn(
            @NotNull String name,
            @NotNull String typeName,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            boolean autoIncrement,
            @NotNull Optional<String> comment,
            int position) {
        this(name, typeName, jdbcType, nullable, primaryKey, foreignKey,
                autoIncrement, false, comment, position, Optional.empty());
    }
}
