package io.github.ns3154.mybatisassistant.settings;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;

import javax.swing.JComponent;
import javax.swing.JPanel;

final class MyBatisAssistantSettingsPanel {
    private final JBCheckBox showNotifications = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.show.notifications"));
    private final JBCheckBox allowNetworkAccess = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.allow.network"));
    private final JBCheckBox experimentalFeatures = new JBCheckBox(
            MyBatisAssistantBundle.message("settings.experimental.features"));
    private final JPanel panel;

    MyBatisAssistantSettingsPanel() {
        panel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel(MyBatisAssistantBundle.message("settings.privacy.description")))
                .addComponent(showNotifications)
                .addComponent(allowNetworkAccess)
                .addComponent(experimentalFeatures)
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
    }

    boolean isModifiedFrom(MyBatisAssistantSettings settings) {
        return showNotifications.isSelected() != settings.isShowNotifications()
                || allowNetworkAccess.isSelected() != settings.isNetworkAccessAllowed()
                || experimentalFeatures.isSelected() != settings.isExperimentalFeaturesEnabled();
    }

    void applyTo(MyBatisAssistantSettings settings) {
        settings.update(
                showNotifications.isSelected(),
                allowNetworkAccess.isSelected(),
                experimentalFeatures.isSelected()
        );
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
}
