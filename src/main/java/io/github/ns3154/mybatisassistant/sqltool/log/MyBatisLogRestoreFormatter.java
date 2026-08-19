package io.github.ns3154.mybatisassistant.sqltool.log;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 将结构化报告渲染为可复制且明确标注风险的中文文本。
 */
public final class MyBatisLogRestoreFormatter {
    private MyBatisLogRestoreFormatter() {
    }

    public static @NotNull String format(@NotNull MyBatisLogRestoreReport report) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < report.statements().size(); index++) {
            MyBatisRestoredStatement statement = report.statements().get(index);
            MyBatisSqlRiskAssessment risk = statement.riskAssessment();
            output.append(MyBatisAssistantBundle.message(
                            "sqltool.log.output.result", index + 1))
                    .append(MyBatisAssistantBundle.message("sqltool.log.output.context"))
                    .append(statement.context())
                    .append(MyBatisAssistantBundle.message("sqltool.log.output.lines"))
                    .append(statement.preparingLine())
                    .append('/').append(statement.parametersLine()).append('\n')
                    .append(MyBatisAssistantBundle.message("sqltool.log.output.risk"))
                    .append(risk.risk().displayName())
                    .append(MyBatisAssistantBundle.message("sqltool.log.output.statement.count"))
                    .append(risk.statementCount())
                    .append(MyBatisAssistantBundle.message("sqltool.log.output.confirmation"))
                    .append(MyBatisAssistantBundle.message(risk.confirmationRequired()
                            ? "common.yes" : "common.no")).append('\n')
                    .append(statement.sql()).append('\n').append('\n');
        }
        if (!report.diagnostics().isEmpty()) {
            output.append(MyBatisAssistantBundle.message("sqltool.log.output.diagnostics"))
                    .append('\n');
            for (MyBatisLogDiagnostic diagnostic : report.diagnostics()) {
                output.append("- [").append(diagnostic.code()).append("] ")
                        .append(diagnostic.message()).append('\n');
            }
        }
        if (output.isEmpty()) {
            return MyBatisAssistantBundle.message("sqltool.log.output.empty");
        }
        return output.toString().stripTrailing();
    }
}
