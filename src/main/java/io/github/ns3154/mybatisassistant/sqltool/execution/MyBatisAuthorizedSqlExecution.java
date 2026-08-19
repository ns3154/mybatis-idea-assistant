package io.github.ns3154.mybatisassistant.sqltool.execution;

import org.jetbrains.annotations.NotNull;

/**
 * 已通过风险确认的短生命周期执行授权。
 */
public record MyBatisAuthorizedSqlExecution(@NotNull MyBatisSqlExecutionPlan plan) {
    @Override
    public String toString() {
        return "MyBatisAuthorizedSqlExecution[" + plan + "]";
    }
}
