package io.github.ns3154.mybatisassistant.sqltool.conversion;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * SELECT 投影清单解析结果。
 */
public sealed interface MyBatisSelectProjectionResult {
    record Success(@NotNull List<MyBatisSelectColumn> columns)
            implements MyBatisSelectProjectionResult {
        public Success {
            columns = List.copyOf(columns);
        }
    }

    record Failure(int offset, @NotNull String message)
            implements MyBatisSelectProjectionResult {
    }
}
