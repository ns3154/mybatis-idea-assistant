package io.github.ns3154.mybatisassistant.sqltool.execution;

import org.jetbrains.annotations.NotNull;

/**
 * 风险确认结果。
 */
public sealed interface MyBatisSqlExecutionAuthorization {
    record Authorized(@NotNull MyBatisAuthorizedSqlExecution execution)
            implements MyBatisSqlExecutionAuthorization {
    }

    record Rejected(@NotNull String message)
            implements MyBatisSqlExecutionAuthorization {
    }
}
