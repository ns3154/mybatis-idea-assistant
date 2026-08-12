package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * 对不落盘的生成片段提供只读预览，避免把候选代码静默写入项目。
 */
final class MyBatisGeneratedTextPreviewDialog extends DialogWrapper {
    private final JBTextArea preview = new JBTextArea();

    MyBatisGeneratedTextPreviewDialog(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String text) {
        super(project, true);
        preview.setText(text);
        preview.setEditable(false);
        preview.setLineWrap(false);
        preview.setCaretPosition(0);
        setTitle(title);
        setOKButtonText(MyBatisAssistantBundle.message("dialog.button.close"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBScrollPane scrollPane = new JBScrollPane(preview);
        scrollPane.setPreferredSize(new Dimension(820, 520));
        return scrollPane;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }
}
