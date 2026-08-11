package io.github.ns3154.mybatisassistant.sqltool.log;

/**
 * SQL 风险分级结果；多语句即使全部只读也要求再次确认。
 */
public record MyBatisSqlRiskAssessment(
        MyBatisSqlRisk risk,
        int statementCount,
        boolean multipleStatements,
        boolean confirmationRequired,
        boolean structurallyValid) {
}
