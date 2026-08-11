package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;

/**
 * 单条 SELECT 到 MyBatis 预览产物的纯内存转换面板。
 */
final class MyBatisSelectToArtifactsDialog extends DialogWrapper {
    private final Project project;
    private final JBTextArea input = new JBTextArea();
    private final JBTextArea output = new JBTextArea();
    private final JBTextField basePackage = new JBTextField("com.example");
    private final JBTextField mapperName = new JBTextField("UserQueryMapper");
    private final JBTextField methodName = new JBTextField("findUsers");
    private final Action convertAction = new AbstractAction("生成预览") {
        @Override
        public void actionPerformed(ActionEvent event) {
            convert();
        }
    };

    MyBatisSelectToArtifactsDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        input.setLineWrap(false);
        output.setEditable(false);
        output.setLineWrap(false);
        setTitle("SELECT 转 Mapper / XML / ResultMap / Java 类");
        setCancelButtonText("关闭");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel options = new JPanel(new GridLayout(1, 6, JBUI.scale(6), 0));
        options.add(new JBLabel("基础包："));
        options.add(basePackage);
        options.add(new JBLabel("Mapper："));
        options.add(mapperName);
        options.add(new JBLabel("方法："));
        options.add(methodName);
        JPanel content = new JPanel(new GridLayout(2, 1, 0, JBUI.scale(8)));
        content.add(section("单条 SELECT（复杂投影必须 AS 别名）", input));
        content.add(section("只读产物预览", output));
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        root.setBorder(JBUI.Borders.empty(6));
        root.add(options, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(1_020, 720));
        return root;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{convertAction, getCancelAction()};
    }

    private JPanel section(String title, JBTextArea area) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    private void convert() {
        String rendered = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> MyBatisSelectToArtifactsAction.render(
                        input.getText(),
                        basePackage.getText().strip(),
                        mapperName.getText().strip(),
                        methodName.getText().strip()),
                "在本地转换 SELECT",
                true,
                project);
        output.setText(rendered);
        output.setCaretPosition(0);
    }
}
