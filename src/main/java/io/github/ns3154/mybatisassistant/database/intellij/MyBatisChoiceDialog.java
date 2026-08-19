package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.function.Function;

/**
 * 使用公开 Swing/DialogWrapper API 的单项选择对话框。
 */
final class MyBatisChoiceDialog<T> extends DialogWrapper {
    private final List<T> values;
    private final JComboBox<String> choices;
    private final String message;

    MyBatisChoiceDialog(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String message,
            @NotNull List<T> values,
            @NotNull Function<T, String> display) {
        super(project, true);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.choice.error.empty"));
        }
        this.values = List.copyOf(values);
        this.choices = new JComboBox<>(values.stream().map(display).toArray(String[]::new));
        this.message = message;
        setTitle(title);
        setOKButtonText(MyBatisAssistantBundle.message("dialog.button.ok"));
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JBLabel(message), BorderLayout.NORTH);
        panel.add(choices, BorderLayout.CENTER);
        return panel;
    }

    @NotNull T selectedValue() {
        return values.get(choices.getSelectedIndex());
    }
}
