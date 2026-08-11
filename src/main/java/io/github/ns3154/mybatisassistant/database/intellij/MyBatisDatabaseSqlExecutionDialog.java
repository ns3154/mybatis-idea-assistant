package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
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
        sql.getEmptyText().setText("输入一条 SQL；默认仅建议执行 SELECT/WITH/EXPLAIN");
        parameters.setLineWrap(false);
        parameters.getEmptyText().setText("每行一个参数，例如 LONG:1、STRING:张三、NULL:VARCHAR");
        setTitle("在 " + dataSourceName + " 上快速执行 SQL");
        setOKButtonText("校验并执行");
        setCancelButtonText("取消");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(true, 0.68f);
        splitter.setFirstComponent(section("SQL（一次仅允许一条）", sql));
        splitter.setSecondComponent(section("参数面板（TYPE:value）", parameters));
        splitter.setPreferredSize(new Dimension(920, 620));
        return splitter;
    }

    @Override
    protected Action @NotNull [] createActions() {
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
