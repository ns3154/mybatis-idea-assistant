package io.github.ns3154.mybatisassistant.settings;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对非敏感全局设置执行确定性导入导出。
 */
public final class MyBatisAssistantSettingsCodec {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern TOOL_ID = Pattern.compile(
            "[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Set<String> KNOWN_KEYS = Set.of(
            "schemaVersion",
            "showNotifications",
            "allowNetworkAccess",
            "experimentalFeatures",
            "mcpEnabled",
            "mcpPort",
            "mcpWriteToolsEnabled",
            "mcpAllowedTools",
            "uiLocale");
    private static final List<String> SENSITIVE_FRAGMENTS = List.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "privatekey");

    private MyBatisAssistantSettingsCodec() {
    }

    public static @NotNull String encode(
            @NotNull MyBatisAssistantSettings.SettingsState source) {
        MyBatisAssistantSettings.SettingsState state = source.copyAndNormalize();
        return "# MyBatis Assistant non-sensitive settings\n"
                + "schemaVersion=" + state.schemaVersion + '\n'
                + "showNotifications=" + state.showNotifications + '\n'
                + "allowNetworkAccess=" + state.allowNetworkAccess + '\n'
                + "experimentalFeatures=" + state.experimentalFeatures + '\n'
                + "mcpEnabled=" + state.mcpEnabled + '\n'
                + "mcpPort=" + state.mcpPort + '\n'
                + "mcpWriteToolsEnabled=" + state.mcpWriteToolsEnabled + '\n'
                + "mcpAllowedTools=" + String.join(",", state.mcpAllowedTools) + '\n'
                + "uiLocale=" + (state.uiLocale.isEmpty() ? "system" : state.uiLocale) + '\n';
    }

    public static @NotNull MyBatisAssistantSettings.SettingsState decode(
            @NotNull String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("设置导入内容超过 64 KiB 上限");
        }
        Map<String, String> values = parse(content);
        int schemaVersion = integer(values.getOrDefault("schemaVersion", "1"), "schemaVersion");
        if (schemaVersion < 1 || schemaVersion > MyBatisAssistantSettings.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的设置 schemaVersion：" + schemaVersion);
        }

        MyBatisAssistantSettings.SettingsState state = new MyBatisAssistantSettings.SettingsState();
        state.schemaVersion = schemaVersion;
        state.showNotifications = bool(
                values.getOrDefault("showNotifications", "true"), "showNotifications");
        state.allowNetworkAccess = bool(
                values.getOrDefault("allowNetworkAccess", "false"), "allowNetworkAccess");
        state.experimentalFeatures = bool(
                values.getOrDefault("experimentalFeatures", "false"), "experimentalFeatures");
        if (schemaVersion >= 2) {
            state.mcpEnabled = bool(
                    values.getOrDefault("mcpEnabled", "false"), "mcpEnabled");
            state.mcpPort = port(values.getOrDefault("mcpPort", "0"));
            state.mcpWriteToolsEnabled = bool(
                    values.getOrDefault("mcpWriteToolsEnabled", "false"),
                    "mcpWriteToolsEnabled");
            state.mcpAllowedTools = tools(values.getOrDefault(
                    "mcpAllowedTools",
                    String.join(",", MyBatisAssistantSettings.DEFAULT_MCP_ALLOWED_TOOLS)));
            state.uiLocale = locale(values.getOrDefault("uiLocale", "system"));
        }
        return state.copyAndNormalize();
    }

    private static @NotNull Map<String, String> parse(@NotNull String content) {
        Map<String, String> values = new HashMap<>();
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("设置导入第 " + (index + 1) + " 行格式无效");
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            rejectSensitiveKey(key);
            if (!KNOWN_KEYS.contains(key)) {
                throw new IllegalArgumentException("设置导入包含未知字段：" + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("设置导入包含重复字段：" + key);
            }
        }
        return values;
    }

    private static void rejectSensitiveKey(@NotNull String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        if (SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("设置导入不得包含敏感字段：" + key);
        }
    }

    private static boolean bool(@NotNull String value, @NotNull String key) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("设置字段 " + key + " 必须为 true 或 false");
        };
    }

    private static int integer(@NotNull String value, @NotNull String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("设置字段 " + key + " 必须为整数", failure);
        }
    }

    private static int port(@NotNull String value) {
        int port = integer(value, "mcpPort");
        if (port != 0 && (port < 1024 || port > 65535)) {
            throw new IllegalArgumentException("mcpPort 必须为 0 或 1024～65535");
        }
        return port;
    }

    private static @NotNull List<String> tools(@NotNull String value) {
        if (value.isBlank()) {
            return List.of();
        }
        String[] candidates = value.split(",", -1);
        if (candidates.length > 64) {
            throw new IllegalArgumentException("MCP 工具白名单超过 64 项上限");
        }
        List<String> tools = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String candidate : candidates) {
            String tool = candidate.trim();
            if (!TOOL_ID.matcher(tool).matches()) {
                throw new IllegalArgumentException("MCP 工具 ID 无效：" + tool);
            }
            if (!unique.add(tool)) {
                throw new IllegalArgumentException("MCP 工具白名单包含重复项：" + tool);
            }
            tools.add(tool);
        }
        tools.sort(String::compareTo);
        return List.copyOf(tools);
    }

    private static @NotNull String locale(@NotNull String value) {
        return switch (value) {
            case "system" -> "";
            case "en", "zh-CN" -> value;
            default -> throw new IllegalArgumentException("uiLocale 只支持 system、en 或 zh-CN");
        };
    }
}
