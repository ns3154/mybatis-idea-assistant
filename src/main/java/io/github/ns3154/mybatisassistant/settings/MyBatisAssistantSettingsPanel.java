package io.github.ns3154.mybatisassistant.settings;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.Arrays;

final class MyBatisAssistantSettingsPanel {
    private final JBCheckBox showNotifications = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.show.notifications"));
    private final JBCheckBox allowNetworkAccess = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.allow.network"));
    private final JBCheckBox experimentalFeatures = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.experimental.features"));
    private final JBCheckBox mcpEnabled = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.mcp.enabled"));
    private final JBTextField mcpPort = new JBTextField("0");
    private final JBCheckBox mcpWriteToolsEnabled = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.mcp.write.tools"));
    private final JBTextField mcpAllowedTools = new JBTextField();
    private final JComboBox<String> uiLocale = new JComboBox<>(new String[]{
            "system", "zh-CN", "en"
    });
    private final JButton importSettings = new JButton(
            MyBatisAssistantBundle.message("settings.import"));
    private final JButton exportSettings = new JButton(
            MyBatisAssistantBundle.message("settings.export"));
    private final JButton restoreDefaults = new JButton(
            MyBatisAssistantBundle.message("settings.restore.defaults"));
    private final JPanel panel;
    private Runnable importAction = () -> { };
    private Runnable exportAction = () -> { };
    private Runnable restoreDefaultsAction = () -> { };

    MyBatisAssistantSettingsPanel() {
        importSettings.addActionListener(ignored -> importAction.run());
        exportSettings.addActionListener(ignored -> exportAction.run());
        restoreDefaults.addActionListener(ignored -> restoreDefaultsAction.run());
        JPanel transferActions = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        transferActions.add(importSettings);
        transferActions.add(exportSettings);
        transferActions.add(restoreDefaults);
        panel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel(MyBatisAssistantBundle.message("settings.privacy.description")))
                .addComponent(showNotifications)
                .addComponent(allowNetworkAccess)
                .addComponent(experimentalFeatures)
                .addSeparator()
                .addComponent(new JBLabel(MyBatisAssistantBundle.message(
                        "settings.mcp.description")))
                .addComponent(mcpEnabled)
                .addLabeledComponent(MyBatisAssistantBundle.message("settings.mcp.port"), mcpPort)
                .addComponent(mcpWriteToolsEnabled)
                .addLabeledComponent(
                        MyBatisAssistantBundle.message("settings.mcp.allowed.tools"),
                        mcpAllowedTools)
                .addLabeledComponent(MyBatisAssistantBundle.message("settings.ui.locale"), uiLocale)
                .addSeparator()
                .addComponent(transferActions)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    JComponent getComponent() {
        return panel;
    }

    JComponent getPreferredFocusedComponent() {
        return showNotifications;
    }

    void resetFrom(MyBatisAssistantSettings settings) {
        showNotifications.setSelected(settings.isShowNotifications());
        allowNetworkAccess.setSelected(settings.isNetworkAccessAllowed());
        experimentalFeatures.setSelected(settings.isExperimentalFeaturesEnabled());
        mcpEnabled.setSelected(settings.isMcpEnabled());
        mcpPort.setText(Integer.toString(settings.getMcpPort()));
        mcpWriteToolsEnabled.setSelected(settings.isMcpWriteToolsEnabled());
        mcpAllowedTools.setText(String.join(",", settings.getMcpAllowedTools()));
        uiLocale.setSelectedItem(settings.getUiLocale().isEmpty()
                ? "system"
                : settings.getUiLocale());
    }

    boolean isModifiedFrom(MyBatisAssistantSettings settings) {
        return showNotifications.isSelected() != settings.isShowNotifications()
                || allowNetworkAccess.isSelected() != settings.isNetworkAccessAllowed()
                || experimentalFeatures.isSelected() != settings.isExperimentalFeaturesEnabled()
                || mcpEnabled.isSelected() != settings.isMcpEnabled()
                || !mcpPort.getText().trim().equals(Integer.toString(settings.getMcpPort()))
                || mcpWriteToolsEnabled.isSelected() != settings.isMcpWriteToolsEnabled()
                || !normalizedToolsText().equals(String.join(",", settings.getMcpAllowedTools()))
                || !selectedLocale().equals(settings.getUiLocale().isEmpty()
                        ? "system"
                        : settings.getUiLocale());
    }

    void applyTo(MyBatisAssistantSettings settings) {
        settings.replace(stateFromControls());
    }

    void setShowNotifications(boolean selected) {
        showNotifications.setSelected(selected);
    }

    void setAllowNetworkAccess(boolean selected) {
        allowNetworkAccess.setSelected(selected);
    }

    void setExperimentalFeatures(boolean selected) {
        experimentalFeatures.setSelected(selected);
    }

    void setImportAction(Runnable action) {
        importAction = action;
    }

    void setExportAction(Runnable action) {
        exportAction = action;
    }

    void setRestoreDefaultsAction(Runnable action) {
        restoreDefaultsAction = action;
    }

    void restoreDefaults() {
        resetFromState(new MyBatisAssistantSettings.SettingsState());
    }

    void resetFromState(MyBatisAssistantSettings.SettingsState state) {
        MyBatisAssistantSettings.SettingsState normalized = state.copyAndNormalize();
        showNotifications.setSelected(normalized.showNotifications);
        allowNetworkAccess.setSelected(normalized.allowNetworkAccess);
        experimentalFeatures.setSelected(normalized.experimentalFeatures);
        mcpEnabled.setSelected(normalized.mcpEnabled);
        mcpPort.setText(Integer.toString(normalized.mcpPort));
        mcpWriteToolsEnabled.setSelected(normalized.mcpWriteToolsEnabled);
        mcpAllowedTools.setText(String.join(",", normalized.mcpAllowedTools));
        uiLocale.setSelectedItem(normalized.uiLocale.isEmpty() ? "system" : normalized.uiLocale);
    }

    String exportText() {
        return MyBatisAssistantSettingsCodec.encode(stateFromControls());
    }

    void importText(String text) {
        resetFromState(MyBatisAssistantSettingsCodec.decode(text));
    }

    private MyBatisAssistantSettings.SettingsState stateFromControls() {
        String selectedLocale = selectedLocale();
        String encoded = "schemaVersion=2\n"
                + "showNotifications=" + showNotifications.isSelected() + '\n'
                + "allowNetworkAccess=" + allowNetworkAccess.isSelected() + '\n'
                + "experimentalFeatures=" + experimentalFeatures.isSelected() + '\n'
                + "mcpEnabled=" + mcpEnabled.isSelected() + '\n'
                + "mcpPort=" + mcpPort.getText().trim() + '\n'
                + "mcpWriteToolsEnabled=" + mcpWriteToolsEnabled.isSelected() + '\n'
                + "mcpAllowedTools=" + normalizedToolsText() + '\n'
                + "uiLocale=" + selectedLocale + '\n';
        return MyBatisAssistantSettingsCodec.decode(encoded);
    }

    private String normalizedToolsText() {
        return String.join(",", Arrays.stream(mcpAllowedTools.getText().split(",", -1))
                .map(String::trim)
                .toList());
    }

    private String selectedLocale() {
        Object selected = uiLocale.getSelectedItem();
        return selected == null ? "system" : selected.toString();
    }
}
