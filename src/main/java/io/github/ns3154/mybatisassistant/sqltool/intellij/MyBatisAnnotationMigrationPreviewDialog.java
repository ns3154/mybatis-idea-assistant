package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * 在 Java 与 XML 同时改写前展示两个文件的完整前后文本。
 */
final class MyBatisAnnotationMigrationPreviewDialog extends DialogWrapper {
    private final MyBatisGenerationPlan plan;

    MyBatisAnnotationMigrationPreviewDialog(
            @NotNull Project project,
            @NotNull MyBatisGenerationPlan plan) {
        super(project, true);
        this.plan = plan;
        setTitle(MyBatisAssistantBundle.message("sqltool.annotation.preview.title"));
        setOKButtonText(MyBatisAssistantBundle.message("sqltool.annotation.button.update"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.cancel"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            JBSplitter splitter = new JBSplitter(false, 0.5f);
            splitter.setFirstComponent(textPanel(
                    MyBatisAssistantBundle.message("sqltool.annotation.current.file"),
                    entry.existingText().orElse("")));
            splitter.setSecondComponent(textPanel(
                    MyBatisAssistantBundle.message("sqltool.annotation.migration.candidate"),
                    entry.proposedText().orElse("")));
            tabs.addTab(entry.artifact().relativePath(), splitter);
        }
        tabs.setPreferredSize(new Dimension(1180, 700));
        return tabs;
    }

    private static @NotNull JPanel textPanel(
            @NotNull String title,
            @NotNull String text) {
        JBTextArea area = new JBTextArea(text);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setBorder(JBUI.Borders.empty(6));
        panel.add(new JBLabel(title), BorderLayout.NORTH);
        panel.add(new JBScrollPane(area), BorderLayout.CENTER);
        return panel;
    }
}
