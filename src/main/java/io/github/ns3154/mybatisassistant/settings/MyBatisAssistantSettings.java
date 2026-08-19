package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings",
        storages = @Storage("mybatisAssistant.xml")
)
public final class MyBatisAssistantSettings
        implements PersistentStateComponent<MyBatisAssistantSettings.SettingsState> {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final List<String> DEFAULT_MCP_ALLOWED_TOOLS = List.of(
            "database.data_sources",
            "database.schema",
            "mapper.list",
            "parameter.describe",
            "reference.find",
            "statement.list");

    private volatile SettingsState state = new SettingsState();

    public static MyBatisAssistantSettings getInstance() {
        return ApplicationManager.getApplication().getService(MyBatisAssistantSettings.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state.copyAndNormalize();
    }

    @Override
    public void loadState(@NotNull SettingsState loadedState) {
        state = loadedState.copyAndNormalize();
        publishChanged();
    }

    public void update(boolean showNotifications, boolean allowNetworkAccess, boolean experimentalFeatures) {
        SettingsState updated = state.copyAndNormalize();
        updated.showNotifications = showNotifications;
        updated.allowNetworkAccess = allowNetworkAccess;
        updated.experimentalFeatures = experimentalFeatures;
        state = updated.copyAndNormalize();
        publishChanged();
    }

    public void replace(@NotNull SettingsState updatedState) {
        state = updatedState.copyAndNormalize();
        publishChanged();
    }

    public void resetToDefaults() {
        state = new SettingsState();
        publishChanged();
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

    public boolean isMcpEnabled() {
        return state.mcpEnabled;
    }

    public int getMcpPort() {
        return state.mcpPort;
    }

    public boolean isMcpWriteToolsEnabled() {
        return state.mcpWriteToolsEnabled;
    }

    public @NotNull List<String> getMcpAllowedTools() {
        return List.copyOf(state.mcpAllowedTools);
    }

    public @NotNull String getUiLocale() {
        return state.uiLocale;
    }

    private void publishChanged() {
        var application = ApplicationManager.getApplication();
        if (application != null && !application.isDisposed()) {
            application.getMessageBus()
                    .syncPublisher(MyBatisAssistantSettingsListener.TOPIC)
                    .settingsChanged(state.copyAndNormalize());
        }
    }

    public static final class SettingsState {
        public int schemaVersion = CURRENT_SCHEMA_VERSION;
        public boolean showNotifications = true;
        public boolean allowNetworkAccess;
        public boolean experimentalFeatures;
        public boolean mcpEnabled;
        public int mcpPort;
        public boolean mcpWriteToolsEnabled;
        public List<String> mcpAllowedTools = new ArrayList<>(DEFAULT_MCP_ALLOWED_TOOLS);
        public String uiLocale = "";

        public @NotNull SettingsState copyAndNormalize() {
            SettingsState copy = new SettingsState();
            copy.schemaVersion = CURRENT_SCHEMA_VERSION;
            copy.showNotifications = showNotifications;
            copy.allowNetworkAccess = allowNetworkAccess;
            copy.experimentalFeatures = experimentalFeatures;
            copy.mcpEnabled = mcpEnabled;
            copy.mcpPort = mcpPort == 0 || mcpPort >= 1024 && mcpPort <= 65535
                    ? mcpPort
                    : 0;
            copy.mcpWriteToolsEnabled = mcpWriteToolsEnabled;
            copy.mcpAllowedTools = normalizeTools(mcpAllowedTools);
            copy.uiLocale = normalizeLocale(uiLocale);
            return copy;
        }

        private static @NotNull List<String> normalizeTools(List<String> tools) {
            if (tools == null) {
                return new ArrayList<>(DEFAULT_MCP_ALLOWED_TOOLS);
            }
            return tools.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(tool -> !tool.isEmpty())
                    .filter(tool -> tool.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
                    .distinct()
                    .sorted()
                    .limit(64)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        private static @NotNull String normalizeLocale(String locale) {
            if (locale == null || locale.isBlank() || "system".equalsIgnoreCase(locale)) {
                return "";
            }
            return switch (locale.trim()) {
                case "en", "zh-CN" -> locale.trim();
                default -> "";
            };
        }
    }
}
