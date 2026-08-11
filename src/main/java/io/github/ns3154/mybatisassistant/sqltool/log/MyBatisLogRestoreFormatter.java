package io.github.ns3154.mybatisassistant.sqltool.log;

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
            output.append("-- 结果 ").append(index + 1)
                    .append("｜上下文：").append(statement.context())
                    .append("｜日志行：").append(statement.preparingLine())
                    .append('/').append(statement.parametersLine()).append('\n')
                    .append("-- 风险：").append(risk.risk().displayName())
                    .append("｜语句数：").append(risk.statementCount())
                    .append("｜后续执行需确认：")
                    .append(risk.confirmationRequired() ? "是" : "否").append('\n')
                    .append(statement.sql()).append('\n').append('\n');
        }
        if (!report.diagnostics().isEmpty()) {
            output.append("诊断：\n");
            for (MyBatisLogDiagnostic diagnostic : report.diagnostics()) {
                output.append("- [").append(diagnostic.code()).append("] ")
                        .append(diagnostic.message()).append('\n');
            }
        }
        if (output.isEmpty()) {
            return "未发现可还原的 MyBatis Preparing/Parameters 日志。";
        }
        return output.toString().stripTrailing();
    }
}
