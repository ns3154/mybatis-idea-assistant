package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
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
 * 用户显式输入 DDL、基础包名和方言的只读转换面板。
 */
final class MyBatisDdlToArtifactsDialog extends DialogWrapper {
    private final Project project;
    private final JBTextArea input = new JBTextArea();
    private final JBTextArea output = new JBTextArea();
    private final JBTextField basePackage = new JBTextField("com.example");
    private final ComboBox<MyBatisSqlDialect> dialect = new ComboBox<>(
            MyBatisSqlDialect.values());
    private final Action convertAction = new AbstractAction(MyBatisAssistantBundle.message(
            "dialog.button.generate.preview")) {
        @Override
        public void actionPerformed(ActionEvent event) {
            convert();
        }
    };

    MyBatisDdlToArtifactsDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        input.setLineWrap(false);
        output.setEditable(false);
        output.setLineWrap(false);
        dialect.setSelectedItem(MyBatisSqlDialect.MYSQL);
        setTitle(MyBatisAssistantBundle.message("sqltool.ddl.dialog.title"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.close"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel options = new JPanel(new GridLayout(1, 4, JBUI.scale(8), 0));
        options.add(new JBLabel(MyBatisAssistantBundle.message("sqltool.label.base.package")));
        options.add(basePackage);
        options.add(new JBLabel(MyBatisAssistantBundle.message("sqltool.label.target.dialect")));
        options.add(dialect);
        JPanel content = new JPanel(new GridLayout(2, 1, 0, JBUI.scale(8)));
        content.add(section(MyBatisAssistantBundle.message("sqltool.ddl.section.input"), input));
        content.add(section(MyBatisAssistantBundle.message("sqltool.ddl.section.preview"), output));
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        root.setBorder(JBUI.Borders.empty(6));
        root.add(options, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(980, 700));
        return root;
    }

    @Override
    protected @NotNull Action[] createActions() {
        return new Action[]{convertAction, getCancelAction()};
    }

    private JPanel section(String title, JBTextArea area) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    private void convert() {
        MyBatisSqlDialect selected = dialect.getSelectedItem() instanceof MyBatisSqlDialect value
                ? value : MyBatisSqlDialect.GENERIC;
        String rendered = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> MyBatisDdlToArtifactsAction.render(
                        input.getText(), selected, basePackage.getText().strip()),
                MyBatisAssistantBundle.message("sqltool.ddl.progress"),
                true,
                project);
        output.setText(rendered);
        output.setCaretPosition(0);
    }
}
