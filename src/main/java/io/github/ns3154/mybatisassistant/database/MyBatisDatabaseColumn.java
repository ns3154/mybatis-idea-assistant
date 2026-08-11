package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

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
        int position) {
    public MyBatisDatabaseColumn {
        if (name.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (typeName.isBlank()) {
            typeName = "UNKNOWN";
        }
        if (position < 0) {
            throw new IllegalArgumentException("列位置不能为负数");
        }
    }
}
