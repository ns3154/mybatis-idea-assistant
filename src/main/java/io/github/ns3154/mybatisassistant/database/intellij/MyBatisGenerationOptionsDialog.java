package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfigurationCodec;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationTemplateGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 数据库生成的显式配置页；配置文本可复制到版本库并确定性导入。
 */
final class MyBatisGenerationOptionsDialog extends DialogWrapper {
    private final JBTextField basePackage = new JBTextField("com.example");
    private final JBTextField javaRoot = new JBTextField("src/main/java");
    private final JBTextField resourceRoot = new JBTextField("src/main/resources");
    private final JBTextField tablePrefix = new JBTextField();
    private final JBTextField entitySuffix = new JBTextField();
    private final JComboBox<MyBatisGenerationTemplateGroup> templateGroup =
            new JComboBox<>(MyBatisGenerationTemplateGroup.values());
    private final Map<MyBatisGenerationArtifactKind, JBCheckBox> artifactBoxes =
            new EnumMap<>(MyBatisGenerationArtifactKind.class);
    private final JBCheckBox comments = new JBCheckBox("生成数据库注释", true);
    private final JBCheckBox escapeKeywords = new JBCheckBox("转义 SQL 关键字", true);
    private final JBTextArea configurationText = new JBTextArea(9, 72);
    private MyBatisGenerationConfiguration importedConfiguration =
            MyBatisGenerationConfiguration.standard("com.example");

    MyBatisGenerationOptionsDialog(@NotNull Project project) {
        super(project, true);
        for (MyBatisGenerationArtifactKind kind : MyBatisGenerationArtifactKind.values()) {
            artifactBoxes.put(kind, new JBCheckBox(displayName(kind), true));
        }
        configurationText.setLineWrap(false);
        setTitle("生成 MyBatis 代码");
        setOKButtonText("预览");
        setResizable(true);
        init();
        exportConfiguration();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel artifacts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        artifactBoxes.values().forEach(artifacts::add);
        JPanel importExport = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton importButton = new JButton("导入上方配置");
        importButton.addActionListener(event -> importConfiguration());
        JButton exportButton = new JButton("导出当前配置");
        exportButton.addActionListener(event -> exportConfiguration());
        importExport.add(importButton);
        importExport.add(exportButton);
        JPanel configPanel = new JPanel(new BorderLayout(0, 6));
        JBScrollPane scrollPane = new JBScrollPane(configurationText);
        scrollPane.setPreferredSize(new Dimension(720, 180));
        configPanel.add(scrollPane, BorderLayout.CENTER);
        configPanel.add(importExport, BorderLayout.SOUTH);
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("基础包名：", basePackage)
                .addLabeledComponent("Java 源码根目录：", javaRoot)
                .addLabeledComponent("资源根目录：", resourceRoot)
                .addLabeledComponent("模板组：", templateGroup)
                .addLabeledComponent("表名前缀：", tablePrefix)
                .addLabeledComponent("实体后缀：", entitySuffix)
                .addLabeledComponent("生成文件：", artifacts)
                .addComponent(comments)
                .addComponent(escapeKeywords)
                .addSeparator()
                .addLabeledComponent("可导入/导出的配置文本：", configPanel)
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        try {
            configuration();
            return null;
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage(), basePackage);
        }
    }

    @NotNull MyBatisGenerationConfiguration configuration() {
        Set<MyBatisGenerationArtifactKind> artifacts = EnumSet.noneOf(
                MyBatisGenerationArtifactKind.class);
        artifactBoxes.forEach((kind, box) -> {
            if (box.isSelected()) {
                artifacts.add(kind);
            }
        });
        return new MyBatisGenerationConfiguration(
                basePackage.getText(),
                javaRoot.getText(),
                resourceRoot.getText(),
                artifacts,
                (MyBatisGenerationTemplateGroup) templateGroup.getSelectedItem(),
                tablePrefix.getText(),
                entitySuffix.getText(),
                comments.isSelected(),
                escapeKeywords.isSelected(),
                importedConfiguration.excludedColumns(),
                importedConfiguration.columnOverrides());
    }

    private void importConfiguration() {
        try {
            applyConfigurationText(configurationText.getText());
            setErrorText(null);
        } catch (IllegalArgumentException invalid) {
            Messages.showErrorDialog(getContentPanel(), invalid.getMessage(), "导入生成配置失败");
        }
    }

    private void exportConfiguration() {
        try {
            configurationText.setText(exportConfigurationText());
            configurationText.setCaretPosition(0);
            setErrorText(null);
        } catch (IllegalArgumentException invalid) {
            setErrorText(invalid.getMessage(), basePackage);
        }
    }

    void applyConfigurationText(@NotNull String text) {
        MyBatisGenerationConfiguration decoded =
                MyBatisGenerationConfigurationCodec.decode(text);
        apply(decoded);
        importedConfiguration = decoded;
    }

    @NotNull String exportConfigurationText() {
        MyBatisGenerationConfiguration current = configuration();
        importedConfiguration = current;
        return MyBatisGenerationConfigurationCodec.encode(current);
    }

    private void apply(@NotNull MyBatisGenerationConfiguration configuration) {
        basePackage.setText(configuration.basePackage());
        javaRoot.setText(configuration.javaSourceRoot());
        resourceRoot.setText(configuration.resourceRoot());
        tablePrefix.setText(configuration.tablePrefix());
        entitySuffix.setText(configuration.entitySuffix());
        templateGroup.setSelectedItem(configuration.templateGroup());
        artifactBoxes.forEach((kind, box) -> box.setSelected(
                configuration.artifacts().contains(kind)));
        comments.setSelected(configuration.generateComments());
        escapeKeywords.setSelected(configuration.escapeSqlKeywords());
    }

    private static @NotNull String displayName(MyBatisGenerationArtifactKind kind) {
        return switch (kind) {
            case ENTITY -> "Entity";
            case MAPPER -> "Mapper";
            case XML -> "XML";
            case SERVICE -> "Service";
        };
    }
}
