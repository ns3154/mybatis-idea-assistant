package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisLogRestoreFormatter;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisLogSqlRestorer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;

/**
 * 只处理用户粘贴文本的本地对话框，不记录输入，也不提供直接执行入口。
 */
final class MyBatisLogSqlRestoreDialog extends DialogWrapper {
    private final Project project;
    private final JBTextArea input = new JBTextArea();
    private final JBTextArea output = new JBTextArea();
    private final Action restoreAction = new AbstractAction("还原 SQL") {
        @Override
        public void actionPerformed(ActionEvent event) {
            restore();
        }
    };

    MyBatisLogSqlRestoreDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        input.setLineWrap(false);
        input.getEmptyText().setText("粘贴 MyBatis Preparing/Parameters 日志；内容仅在本机内存中处理");
        output.setEditable(false);
        output.setLineWrap(false);
        output.getEmptyText().setText("还原结果、诊断与风险等级将在这里显示");
        setTitle("还原 MyBatis 日志 SQL");
        setCancelButtonText("关闭");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(true, 0.5f);
        splitter.setFirstComponent(section("日志输入", input));
        splitter.setSecondComponent(section("还原结果（不会自动执行）", output));
        splitter.setPreferredSize(new Dimension(920, 640));
        return splitter;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{restoreAction, getCancelAction()};
    }

    private JPanel section(String title, JBTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(6));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);
        return panel;
    }

    private void restore() {
        String rendered = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> MyBatisLogRestoreFormatter.format(
                        MyBatisLogSqlRestorer.restore(input.getText())),
                "在本地还原 MyBatis 日志 SQL",
                true,
                project);
        output.setText(rendered);
        output.setCaretPosition(0);
    }
}
