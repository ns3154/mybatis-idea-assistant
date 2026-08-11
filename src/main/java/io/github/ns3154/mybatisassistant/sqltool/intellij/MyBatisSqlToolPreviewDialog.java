package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * SQL 工具链共用的只读文本预览。
 */
final class MyBatisSqlToolPreviewDialog extends DialogWrapper {
    private final JBTextArea preview = new JBTextArea();

    MyBatisSqlToolPreviewDialog(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String text) {
        super(project, true);
        preview.setText(text);
        preview.setEditable(false);
        preview.setLineWrap(false);
        preview.setCaretPosition(0);
        setTitle(title);
        setOKButtonText("关闭");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBScrollPane scrollPane = new JBScrollPane(preview);
        scrollPane.setPreferredSize(new Dimension(980, 650));
        return scrollPane;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }
}
