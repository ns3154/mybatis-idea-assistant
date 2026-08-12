package io.github.ns3154.mybatisassistant.sqltool.intellij;

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

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * 在任何文件写入发生前展示格式化前后的完整文本。
 */
final class MyBatisXmlFormatPreviewDialog extends DialogWrapper {
    private final String original;
    private final String formatted;

    MyBatisXmlFormatPreviewDialog(
            @NotNull Project project,
            @NotNull String original,
            @NotNull String formatted) {
        super(project, true);
        this.original = original;
        this.formatted = formatted;
        setTitle(MyBatisAssistantBundle.message("sqltool.format.preview.title"));
        setOKButtonText(MyBatisAssistantBundle.message("sqltool.format.button.apply"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.cancel"));
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(false, 0.5f);
        splitter.setFirstComponent(textPanel(MyBatisAssistantBundle.message(
                "sqltool.format.before"), original));
        splitter.setSecondComponent(textPanel(MyBatisAssistantBundle.message(
                "sqltool.format.after"), formatted));
        splitter.setPreferredSize(new Dimension(1100, 650));
        return splitter;
    }

    private JPanel textPanel(String title, String text) {
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
