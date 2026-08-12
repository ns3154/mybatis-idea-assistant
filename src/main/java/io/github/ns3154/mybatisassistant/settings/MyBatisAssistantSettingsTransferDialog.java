package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.Dimension;

/**
 * 通过可审计文本导入导出非敏感全局设置。
 */
final class MyBatisAssistantSettingsTransferDialog extends DialogWrapper {
    private final boolean importMode;
    private final JBTextArea text = new JBTextArea();
    private MyBatisAssistantSettings.SettingsState importedState;

    private MyBatisAssistantSettingsTransferDialog(boolean importMode, @NotNull String content) {
        super(true);
        this.importMode = importMode;
        text.setText(content);
        text.setEditable(importMode);
        text.setLineWrap(false);
        text.setCaretPosition(0);
        setTitle(MyBatisAssistantBundle.message(importMode
                ? "settings.import.dialog.title"
                : "settings.export.dialog.title"));
        setOKButtonText(MyBatisAssistantBundle.message(importMode
                ? "settings.import.dialog.apply"
                : "settings.export.dialog.close"));
        setResizable(true);
        init();
    }

    static @NotNull MyBatisAssistantSettingsTransferDialog importDialog() {
        return new MyBatisAssistantSettingsTransferDialog(true, "");
    }

    static @NotNull MyBatisAssistantSettingsTransferDialog exportDialog(
            @NotNull String content) {
        return new MyBatisAssistantSettingsTransferDialog(false, content);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBScrollPane scrollPane = new JBScrollPane(text);
        scrollPane.setPreferredSize(new Dimension(760, 480));
        return scrollPane;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (!importMode) {
            return null;
        }
        try {
            MyBatisAssistantSettingsCodec.decode(text.getText());
            return null;
        } catch (IllegalArgumentException failure) {
            return new ValidationInfo(failure.getMessage(), text);
        }
    }

    @Override
    protected void doOKAction() {
        if (importMode) {
            importedState = MyBatisAssistantSettingsCodec.decode(text.getText());
        }
        super.doOKAction();
    }

    @Override
    protected Action @NotNull [] createActions() {
        return importMode
                ? super.createActions()
                : new Action[]{getOKAction()};
    }

    @NotNull MyBatisAssistantSettings.SettingsState importedState() {
        if (!importMode || importedState == null) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "settings.import.error.not.validated"));
        }
        return importedState.copyAndNormalize();
    }

    void setContent(@NotNull String content) {
        text.setText(content);
    }
}
