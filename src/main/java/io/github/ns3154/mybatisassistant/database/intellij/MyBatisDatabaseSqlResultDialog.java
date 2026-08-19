package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * 展示可复制的执行结果与实际 SQL，不展示参数原始值。
 */
final class MyBatisDatabaseSqlResultDialog extends DialogWrapper {
    private final JBTextArea text = new JBTextArea();

    MyBatisDatabaseSqlResultDialog(
            @NotNull Project project,
            @NotNull String sql,
            @NotNull MyBatisSqlExecutionResult result) {
        super(project, true);
        text.setEditable(false);
        text.setLineWrap(false);
        text.setText(render(sql, result));
        text.setCaretPosition(0);
        setTitle(result instanceof MyBatisSqlExecutionResult.Success
                ? MyBatisAssistantBundle.message("database.sql.result.title.success")
                : MyBatisAssistantBundle.message("database.sql.result.title.failure"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.close"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBScrollPane pane = new JBScrollPane(text);
        pane.setPreferredSize(new Dimension(920, 620));
        return pane;
    }

    @Override
    protected @NotNull Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    static @NotNull String render(
            @NotNull String sql,
            @NotNull MyBatisSqlExecutionResult result) {
        StringBuilder output = new StringBuilder(MyBatisAssistantBundle.message(
                "database.sql.result.executed.sql")).append('\n')
                .append(sql).append("\n\n");
        if (result instanceof MyBatisSqlExecutionResult.Failure failure) {
            output.append(MyBatisAssistantBundle.message(
                    "database.sql.result.failure")).append(failure.message()).append('\n');
            if (!failure.sqlState().isBlank()) {
                output.append("SQLState：").append(failure.sqlState()).append('\n');
            }
            if (failure.vendorCode() != 0) {
                output.append(MyBatisAssistantBundle.message(
                        "database.sql.result.vendor.code"))
                        .append(failure.vendorCode()).append('\n');
            }
            return output.toString();
        }
        MyBatisSqlExecutionResult.Success success =
                (MyBatisSqlExecutionResult.Success) result;
        output.append(MyBatisAssistantBundle.message("database.sql.result.duration"))
                .append(success.durationMillis()).append(" ms\n");
        if (success.updateCount() >= 0) {
            output.append(MyBatisAssistantBundle.message("database.sql.result.update.count"))
                    .append(success.updateCount()).append('\n');
        }
        if (!success.columns().isEmpty()) {
            output.append(String.join("\t", success.columns())).append('\n');
            for (var row : success.rows()) {
                output.append(String.join("\t", row)).append('\n');
            }
            if (success.truncated()) {
                output.append('\n').append(MyBatisAssistantBundle.message(
                        "database.sql.result.truncated", 200)).append('\n');
            }
        }
        return output.toString();
    }
}
