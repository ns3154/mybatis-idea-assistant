package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 元数据查询的可选数据源和 schema 约束。
 */
public record MyBatisDatabaseRequest(
        @NotNull Optional<String> dataSourceId,
        @NotNull Optional<String> schemaName) {
    public static @NotNull MyBatisDatabaseRequest all() {
        return new MyBatisDatabaseRequest(Optional.empty(), Optional.empty());
    }
}
