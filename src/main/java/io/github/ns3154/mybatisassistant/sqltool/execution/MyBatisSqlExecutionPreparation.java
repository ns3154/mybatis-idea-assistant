package io.github.ns3154.mybatisassistant.sqltool.execution;

import org.jetbrains.annotations.NotNull;

/**
 * SQL 执行前置检查结果。
 */
public sealed interface MyBatisSqlExecutionPreparation {
    record Ready(@NotNull MyBatisSqlExecutionPlan plan)
            implements MyBatisSqlExecutionPreparation {
    }

    record Rejected(@NotNull String message)
            implements MyBatisSqlExecutionPreparation {
    }
}
