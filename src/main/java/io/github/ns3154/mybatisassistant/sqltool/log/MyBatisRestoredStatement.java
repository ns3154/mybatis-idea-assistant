package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

/**
 * 一组完整 Preparing/Parameters 日志对应的还原结果。
 */
public record MyBatisRestoredStatement(
        @NotNull String context,
        int preparingLine,
        int parametersLine,
        @NotNull String sql,
        @NotNull MyBatisSqlRiskAssessment riskAssessment) {
}
