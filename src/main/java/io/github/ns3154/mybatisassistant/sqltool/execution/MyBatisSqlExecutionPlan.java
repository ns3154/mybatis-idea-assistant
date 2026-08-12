package io.github.ns3154.mybatisassistant.sqltool.execution;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskAssessment;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 通过词法、参数和风险门后的执行计划；字符串化时不输出 SQL 与参数。
 */
public record MyBatisSqlExecutionPlan(
        @NotNull String sql,
        @NotNull List<MyBatisSqlParameter> parameters,
        @NotNull MyBatisSqlRiskAssessment riskAssessment,
        boolean doubleConfirmationRequired) {
    public MyBatisSqlExecutionPlan {
        parameters = List.copyOf(parameters);
    }

    @Override
    public String toString() {
        return "MyBatisSqlExecutionPlan[risk=" + riskAssessment.risk()
                + ", statements=" + riskAssessment.statementCount()
                + ", parameters=" + parameters.size() + ", sql="
                + MyBatisAssistantBundle.message("common.redacted") + "]";
    }
}
