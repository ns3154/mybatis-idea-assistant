package io.github.ns3154.mybatisassistant.sqltool.execution;

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
    public static final String DANGEROUS_CONFIRMATION_PHRASE = "执行危险SQL";

    private MyBatisSqlExecutionPolicy() {
    }

    public static @NotNull MyBatisSqlExecutionPreparation prepare(
            @NotNull String sql,
            @NotNull String parameterPanel) {
        if (sql.isBlank()) {
            return rejected("SQL 不能为空");
        }
        if (sql.length() > MAX_SQL_BYTES
                || sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES) {
            return rejected("SQL 超过 1 MiB 执行上限");
        }
        if (sql.contains("#{") || sql.contains("${")) {
            return rejected("请先把 MyBatis 动态占位符解析为 JDBC 问号占位符");
        }
        if (parameterPanel.length() > MAX_PARAMETER_PANEL_BYTES
                || parameterPanel.getBytes(StandardCharsets.UTF_8).length
                        > MAX_PARAMETER_PANEL_BYTES) {
            return rejected("参数面板超过 2 MiB 执行上限");
        }
        MyBatisSqlRiskAssessment risk = MyBatisSqlRiskClassifier.assess(sql);
        if (!risk.structurallyValid()) {
            return rejected("SQL 词法结构不完整");
        }
        if (risk.statementCount() != 1) {
            return rejected("快速执行一次只允许一条完整 SQL 语句");
        }
        MyBatisSqlPlaceholderAnalysis placeholders =
                MyBatisSqlPlaceholderAnalyzer.analyze(sql);
        if (!placeholders.structurallyValid()) {
            return rejected(placeholders.message());
        }
        MyBatisSqlParameterParseResult parsed =
                MyBatisSqlParameterPanelParser.parse(parameterPanel);
        if (parsed instanceof MyBatisSqlParameterParseResult.Failure failure) {
            return rejected("参数面板第 " + failure.lineNumber() + " 行：" + failure.message());
        }
        var parameters = ((MyBatisSqlParameterParseResult.Success) parsed).parameters();
        if (parameters.size() != placeholders.placeholderCount()) {
            return rejected("JDBC 占位符数量为 " + placeholders.placeholderCount()
                    + "，参数面板提供了 " + parameters.size() + " 个参数");
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
            return authorizationRejected("危险 SQL 风险确认未完成");
        }
        if (!DANGEROUS_CONFIRMATION_PHRASE.equals(typedConfirmation)) {
            return authorizationRejected("二次确认短语不匹配，未授权执行");
        }
        return new MyBatisSqlExecutionAuthorization.Authorized(
                new MyBatisAuthorizedSqlExecution(plan));
    }

    private static MyBatisSqlExecutionPreparation.Rejected rejected(String message) {
        return new MyBatisSqlExecutionPreparation.Rejected(message);
    }

    private static MyBatisSqlExecutionAuthorization.Rejected authorizationRejected(
            String message) {
        return new MyBatisSqlExecutionAuthorization.Rejected(message);
    }
}
