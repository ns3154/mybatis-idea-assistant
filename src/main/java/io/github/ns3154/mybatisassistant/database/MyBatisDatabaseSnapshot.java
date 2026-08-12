package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一个数据源在某一修改世代的只读元数据快照。
 */
public record MyBatisDatabaseSnapshot(
        @NotNull String dataSourceId,
        @NotNull String displayName,
        @NotNull MyBatisSqlDialect dialect,
        @NotNull MyBatisMetadataFreshness freshness,
        long modificationCount,
        @NotNull List<MyBatisDatabaseTable> tables) {
    public MyBatisDatabaseSnapshot {
        if (dataSourceId.isBlank() || displayName.isBlank()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.snapshot.identity.empty"));
        }
        if (modificationCount < 0) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.snapshot.modification.negative"));
        }
        tables = List.copyOf(tables);
    }
}
