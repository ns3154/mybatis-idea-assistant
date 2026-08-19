package io.github.ns3154.mybatisassistant.settings;

import junit.framework.TestCase;

import java.util.List;

public final class MyBatisAssistantSettingsCodecTest extends TestCase {
    public void testDeterministicRoundTripContainsNoSensitiveFields() {
        MyBatisAssistantSettings.SettingsState state = new MyBatisAssistantSettings.SettingsState();
        state.showNotifications = false;
        state.allowNetworkAccess = true;
        state.experimentalFeatures = true;
        state.mcpEnabled = true;
        state.mcpPort = 61234;
        state.mcpWriteToolsEnabled = true;
        state.mcpAllowedTools = List.of("statement.list", "mapper.list");
        state.uiLocale = "en";

        String encoded = MyBatisAssistantSettingsCodec.encode(state);
        MyBatisAssistantSettings.SettingsState decoded =
                MyBatisAssistantSettingsCodec.decode(encoded);

        assertEquals(encoded, MyBatisAssistantSettingsCodec.encode(decoded));
        assertFalse(decoded.showNotifications);
        assertTrue(decoded.allowNetworkAccess);
        assertTrue(decoded.experimentalFeatures);
        assertTrue(decoded.mcpEnabled);
        assertEquals(61234, decoded.mcpPort);
        assertTrue(decoded.mcpWriteToolsEnabled);
        assertEquals(List.of("mapper.list", "statement.list"), decoded.mcpAllowedTools);
        assertEquals("en", decoded.uiLocale);
        String lower = encoded.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("password"));
        assertFalse(lower.contains("token"));
        assertFalse(lower.contains("secret"));
        assertFalse(lower.contains("credential"));
    }

    public void testMigratesSchemaOneWithConservativeDefaults() {
        MyBatisAssistantSettings.SettingsState decoded = MyBatisAssistantSettingsCodec.decode("""
                schemaVersion=1
                showNotifications=false
                allowNetworkAccess=true
                experimentalFeatures=true
                """);

        assertEquals(2, decoded.schemaVersion);
        assertFalse(decoded.showNotifications);
        assertTrue(decoded.allowNetworkAccess);
        assertTrue(decoded.experimentalFeatures);
        assertFalse(decoded.mcpEnabled);
        assertEquals(0, decoded.mcpPort);
        assertFalse(decoded.mcpWriteToolsEnabled);
        assertEquals(MyBatisAssistantSettings.DEFAULT_MCP_ALLOWED_TOOLS,
                decoded.mcpAllowedTools);
        assertEquals("", decoded.uiLocale);
    }

    public void testRejectsInvalidFutureDuplicateUnknownAndSensitiveSettings() {
        expectInvalid("schemaVersion=3\n");
        expectInvalid("schemaVersion=2\nmcpPort=80\n");
        expectInvalid("schemaVersion=2\nmcpEnabled=yes\n");
        expectInvalid("schemaVersion=2\nuiLocale=fr\n");
        expectInvalid("schemaVersion=2\nunknown=true\n");
        expectInvalid("schemaVersion=2\nmcpPort=0\nmcpPort=0\n");
        expectInvalid("schemaVersion=2\npassword=secret\n");
        expectInvalid("schemaVersion=2\nmcpToken=secret\n");
        expectInvalid("schemaVersion=2\nmcpAllowedTools=mapper.list,mapper.list\n");
        expectInvalid("x".repeat(65_537));
    }

    public void testSupportsEmptyWhitelistAndSystemLocale() {
        MyBatisAssistantSettings.SettingsState decoded = MyBatisAssistantSettingsCodec.decode("""
                schemaVersion=2
                mcpAllowedTools=
                uiLocale=system
                """);

        assertEquals(List.of(), decoded.mcpAllowedTools);
        assertEquals("", decoded.uiLocale);
    }

    private static void expectInvalid(String content) {
        try {
            MyBatisAssistantSettingsCodec.decode(content);
            fail("预期设置导入失败");
        } catch (IllegalArgumentException expected) {
            // 预期分支。
        }
    }
}
