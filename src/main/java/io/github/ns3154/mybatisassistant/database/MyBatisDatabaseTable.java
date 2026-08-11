package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 数据源中一个表或视图的稳定快照。
 */
public record MyBatisDatabaseTable(
        @NotNull Optional<String> catalog,
        @NotNull Optional<String> schema,
        @NotNull String name,
        @NotNull List<MyBatisDatabaseColumn> columns) {
    public MyBatisDatabaseTable {
        if (name.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        columns = List.copyOf(columns);
    }
}
