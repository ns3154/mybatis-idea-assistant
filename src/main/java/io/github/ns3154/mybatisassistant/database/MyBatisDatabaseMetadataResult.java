package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 元数据后台加载的类型化结果。
 */
public sealed interface MyBatisDatabaseMetadataResult {
    record Loaded(@NotNull List<MyBatisDatabaseSnapshot> snapshots)
            implements MyBatisDatabaseMetadataResult {
        public Loaded {
            snapshots = List.copyOf(snapshots);
        }
    }

    record Unavailable(@NotNull Reason reason)
            implements MyBatisDatabaseMetadataResult {
    }

    record Failed(@NotNull List<String> providerIds)
            implements MyBatisDatabaseMetadataResult {
        public Failed {
            providerIds = List.copyOf(providerIds);
        }
    }

    enum Reason {
        NO_PROVIDER,
        NO_DATA_SOURCE,
        PROJECT_CLOSED,
        TIMED_OUT
    }
}
