package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * 快速执行输入面板。输入只保留在当前对话框内存中。
 */
final class MyBatisDatabaseSqlExecutionDialog extends DialogWrapper {
    private final JBTextArea sql = new JBTextArea();
    private final JBTextArea parameters = new JBTextArea();

    MyBatisDatabaseSqlExecutionDialog(
            @NotNull Project project,
            @NotNull String dataSourceName) {
        super(project, true);
        sql.setLineWrap(false);
        sql.getEmptyText().setText(MyBatisAssistantBundle.message(
                "database.sql.execution.input.empty"));
        parameters.setLineWrap(false);
        parameters.getEmptyText().setText(MyBatisAssistantBundle.message(
                "database.sql.execution.parameters.empty"));
        setTitle(MyBatisAssistantBundle.message(
                "database.sql.execution.title", dataSourceName));
        setOKButtonText(MyBatisAssistantBundle.message(
                "database.sql.execution.button.execute"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.cancel"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(true, 0.68f);
        splitter.setFirstComponent(section(MyBatisAssistantBundle.message(
                "database.sql.execution.section.sql"), sql));
        splitter.setSecondComponent(section(MyBatisAssistantBundle.message(
                "database.sql.execution.section.parameters"), parameters));
        splitter.setPreferredSize(new Dimension(920, 620));
        return splitter;
    }

    @Override
    protected @NotNull Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }

    @NotNull String sql() {
        return sql.getText();
    }

    @NotNull String parameterPanel() {
        return parameters.getText();
    }

    private JPanel section(String title, JBTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(6));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);
        return panel;
    }
}
