package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * 要求用户显式选择目标方言，不按当前文件或类路径猜测。
 */
final class MyBatisSqlDialectDialog extends DialogWrapper {
    private final ComboBox<MyBatisSqlDialect> dialect = new ComboBox<>(
            MyBatisSqlDialect.values());

    MyBatisSqlDialectDialog(@NotNull Project project, @NotNull String title) {
        super(project, true);
        setTitle(title);
        setOKButtonText(MyBatisAssistantBundle.message("dialog.button.generate.preview"));
        setCancelButtonText(MyBatisAssistantBundle.message("dialog.button.cancel"));
        dialect.setSelectedItem(MyBatisSqlDialect.MYSQL);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JBLabel(MyBatisAssistantBundle.message(
                "sqltool.label.target.database.dialect")), BorderLayout.WEST);
        panel.add(dialect, BorderLayout.CENTER);
        return panel;
    }

    @NotNull MyBatisSqlDialect selectedDialect() {
        Object selected = dialect.getSelectedItem();
        return selected instanceof MyBatisSqlDialect value
                ? value : MyBatisSqlDialect.GENERIC;
    }
}
