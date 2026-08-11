package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@State(
        name = "io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings",
        storages = @Storage("mybatisAssistant.xml")
)
public final class MyBatisAssistantSettings
        implements PersistentStateComponent<MyBatisAssistantSettings.SettingsState> {
    private volatile SettingsState state = new SettingsState();

    public static MyBatisAssistantSettings getInstance() {
        return ApplicationManager.getApplication().getService(MyBatisAssistantSettings.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState loadedState) {
        state = loadedState.copyAndNormalize();
    }

    public void update(boolean showNotifications, boolean allowNetworkAccess, boolean experimentalFeatures) {
        SettingsState updated = new SettingsState();
        updated.showNotifications = showNotifications;
        updated.allowNetworkAccess = allowNetworkAccess;
        updated.experimentalFeatures = experimentalFeatures;
        state = updated;
    }

    public boolean isShowNotifications() {
        return state.showNotifications;
    }

    public boolean isNetworkAccessAllowed() {
        return state.allowNetworkAccess;
    }

    public boolean isExperimentalFeaturesEnabled() {
        return state.experimentalFeatures;
    }

    public static final class SettingsState {
        public int schemaVersion = 1;
        public boolean showNotifications = true;
        public boolean allowNetworkAccess;
        public boolean experimentalFeatures;

        public @NotNull SettingsState copyAndNormalize() {
            SettingsState copy = new SettingsState();
            copy.schemaVersion = 1;
            copy.showNotifications = showNotifications;
            copy.allowNetworkAccess = allowNetworkAccess;
            copy.experimentalFeatures = experimentalFeatures;
            return copy;
        }
    }
}
