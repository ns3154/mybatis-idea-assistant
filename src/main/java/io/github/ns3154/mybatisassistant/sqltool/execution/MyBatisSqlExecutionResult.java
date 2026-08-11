package io.github.ns3154.mybatisassistant.sqltool.execution;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 可复制的执行结果；失败由界面与实际 SQL 一起展示，但生产日志不得记录。
 */
public sealed interface MyBatisSqlExecutionResult {
    record Success(
            @NotNull List<String> columns,
            @NotNull List<List<String>> rows,
            int updateCount,
            boolean truncated,
            long durationMillis) implements MyBatisSqlExecutionResult {
        public Success {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }

        @Override
        public String toString() {
            return "Success[columns=" + columns.size()
                    + ", rows=" + rows.size()
                    + ", updateCount=" + updateCount
                    + ", truncated=" + truncated
                    + ", durationMillis=" + durationMillis
                    + ", data=<已脱敏>]";
        }
    }

    record Failure(
            @NotNull String message,
            @NotNull String sqlState,
            int vendorCode) implements MyBatisSqlExecutionResult {
        @Override
        public String toString() {
            return "Failure[message=<已脱敏>, sqlState=" + sqlState
                    + ", vendorCode=" + vendorCode + "]";
        }
    }
}
