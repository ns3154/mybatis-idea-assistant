package io.github.ns3154.mybatisassistant.sqltool.execution;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlPlaceholderAnalysis;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlPlaceholderAnalyzer;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRisk;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskAssessment;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRiskClassifier;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * 默认只读、单语句、显式参数与危险 SQL 双确认策略。
 */
public final class MyBatisSqlExecutionPolicy {
    public static final int MAX_SQL_BYTES = 1024 * 1024;
    public static final int MAX_PARAMETER_PANEL_BYTES = 2 * 1024 * 1024;

    private MyBatisSqlExecutionPolicy() {
    }

    public static @NotNull MyBatisSqlExecutionPreparation prepare(
            @NotNull String sql,
            @NotNull String parameterPanel) {
        if (sql.isBlank()) {
            return rejected(MyBatisAssistantBundle.message("sqltool.execution.error.sql.empty"));
        }
        if (sql.length() > MAX_SQL_BYTES
                || sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.sql.too.large"));
        }
        if (sql.contains("#{") || sql.contains("${")) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.dynamic.placeholder"));
        }
        if (parameterPanel.length() > MAX_PARAMETER_PANEL_BYTES
                || parameterPanel.getBytes(StandardCharsets.UTF_8).length
                        > MAX_PARAMETER_PANEL_BYTES) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.parameters.too.large"));
        }
        MyBatisSqlRiskAssessment risk = MyBatisSqlRiskClassifier.assess(sql);
        if (!risk.structurallyValid()) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.sql.incomplete"));
        }
        if (risk.statementCount() != 1) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.sql.multiple"));
        }
        MyBatisSqlPlaceholderAnalysis placeholders =
                MyBatisSqlPlaceholderAnalyzer.analyze(sql);
        if (!placeholders.structurallyValid()) {
            return rejected(placeholders.message());
        }
        MyBatisSqlParameterParseResult parsed =
                MyBatisSqlParameterPanelParser.parse(parameterPanel);
        if (parsed instanceof MyBatisSqlParameterParseResult.Failure failure) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.parameter.line",
                    failure.lineNumber(), failure.message()));
        }
        var parameters = ((MyBatisSqlParameterParseResult.Success) parsed).parameters();
        if (parameters.size() != placeholders.placeholderCount()) {
            return rejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.placeholder.count",
                    placeholders.placeholderCount(), parameters.size()));
        }
        boolean dangerous = risk.risk() != MyBatisSqlRisk.READ_ONLY;
        return new MyBatisSqlExecutionPreparation.Ready(new MyBatisSqlExecutionPlan(
                sql, parameters, risk, dangerous));
    }

    public static @NotNull MyBatisSqlExecutionAuthorization authorize(
            @NotNull MyBatisSqlExecutionPlan plan,
            boolean riskConfirmed,
            @NotNull String typedConfirmation) {
        if (!plan.doubleConfirmationRequired()) {
            return new MyBatisSqlExecutionAuthorization.Authorized(
                    new MyBatisAuthorizedSqlExecution(plan));
        }
        if (!riskConfirmed) {
            return authorizationRejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.risk.not.confirmed"));
        }
        if (!dangerousConfirmationPhrase().equals(typedConfirmation)) {
            return authorizationRejected(MyBatisAssistantBundle.message(
                    "sqltool.execution.error.confirmation.mismatch"));
        }
        return new MyBatisSqlExecutionAuthorization.Authorized(
                new MyBatisAuthorizedSqlExecution(plan));
    }

    public static @NotNull String dangerousConfirmationPhrase() {
        return MyBatisAssistantBundle.message("sqltool.execution.confirmation.phrase");
    }

    private static MyBatisSqlExecutionPreparation.Rejected rejected(String message) {
        return new MyBatisSqlExecutionPreparation.Rejected(message);
    }

    private static MyBatisSqlExecutionAuthorization.Rejected authorizationRejected(
            String message) {
        return new MyBatisSqlExecutionAuthorization.Rejected(message);
    }
}
