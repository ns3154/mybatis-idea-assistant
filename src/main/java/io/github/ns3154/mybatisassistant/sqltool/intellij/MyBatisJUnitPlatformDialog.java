package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisJUnitPlatform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * 使用稳定 DialogWrapper API 选择 JUnit 平台。
 */
final class MyBatisJUnitPlatformDialog extends DialogWrapper {
    private final ComboBox<String> platforms;

    MyBatisJUnitPlatformDialog(@NotNull Project project) {
        super(project, true);
        String[] labels = java.util.Arrays.stream(MyBatisJUnitPlatform.values())
                .map(MyBatisJUnitPlatform::displayName)
                .toArray(String[]::new);
        platforms = new ComboBox<>(labels);
        platforms.setSelectedIndex(0);
        setTitle("生成 Mapper JUnit 测试骨架");
        setOKButtonText("生成预览");
        setCancelButtonText("取消");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JBLabel("JUnit 平台："), BorderLayout.WEST);
        panel.add(platforms, BorderLayout.CENTER);
        return panel;
    }

    @NotNull MyBatisJUnitPlatform selectedPlatform() {
        int selected = platforms.getSelectedIndex();
        return selected >= 0 && selected < MyBatisJUnitPlatform.values().length
                ? MyBatisJUnitPlatform.values()[selected]
                : MyBatisJUnitPlatform.JUNIT_5;
    }
}
