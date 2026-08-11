package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

public final class MyBatisAssistantSettingsConfigurable implements SearchableConfigurable {
    public static final String ID = "io.github.ns3154.mybatisassistant.settings";

    private MyBatisAssistantSettingsPanel settingsPanel;

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls String getDisplayName() {
        return MyBatisAssistantBundle.message("settings.display.name");
    }

    @Override
    public @Nullable JComponent createComponent() {
        settingsPanel = new MyBatisAssistantSettingsPanel();
        settingsPanel.resetFrom(MyBatisAssistantSettings.getInstance());
        return settingsPanel.getComponent();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return settingsPanel == null ? null : settingsPanel.getPreferredFocusedComponent();
    }

    @Override
    public boolean isModified() {
        return settingsPanel != null && settingsPanel.isModifiedFrom(MyBatisAssistantSettings.getInstance());
    }

    @Override
    public void apply() {
        if (settingsPanel != null) {
            settingsPanel.applyTo(MyBatisAssistantSettings.getInstance());
        }
    }

    @Override
    public void reset() {
        if (settingsPanel != null) {
            settingsPanel.resetFrom(MyBatisAssistantSettings.getInstance());
        }
    }

    @Override
    public void disposeUIResources() {
        settingsPanel = null;
    }

    MyBatisAssistantSettingsPanel getSettingsPanel() {
        return settingsPanel;
    }
}
