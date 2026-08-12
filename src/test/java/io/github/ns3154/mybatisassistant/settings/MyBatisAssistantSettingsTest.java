package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MyBatisAssistantSettingsTest extends BasePlatformTestCase {
    private MyBatisAssistantSettings.SettingsState originalState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalState = MyBatisAssistantSettings.getInstance().getState().copyAndNormalize();
        MyBatisAssistantSettings.getInstance().loadState(new MyBatisAssistantSettings.SettingsState());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MyBatisAssistantSettings.getInstance().loadState(originalState);
        } finally {
            super.tearDown();
        }
    }

    public void testDefaultsAreOfflineAndConservative() {
        MyBatisAssistantSettings settings = MyBatisAssistantSettings.getInstance();

        assertTrue(settings.isShowNotifications());
        assertFalse(settings.isNetworkAccessAllowed());
        assertFalse(settings.isExperimentalFeaturesEnabled());
        assertFalse(settings.isMcpEnabled());
        assertEquals(0, settings.getMcpPort());
        assertFalse(settings.isMcpWriteToolsEnabled());
        assertEquals(MyBatisAssistantSettings.DEFAULT_MCP_ALLOWED_TOOLS,
                settings.getMcpAllowedTools());
        assertEquals("", settings.getUiLocale());
        assertEquals(2, settings.getState().schemaVersion);
    }

    public void testLoadsAndNormalizesPersistentState() {
        MyBatisAssistantSettings.SettingsState loaded = new MyBatisAssistantSettings.SettingsState();
        loaded.schemaVersion = -10;
        loaded.showNotifications = false;
        loaded.allowNetworkAccess = true;
        loaded.experimentalFeatures = true;
        loaded.mcpEnabled = true;
        loaded.mcpPort = 80;
        loaded.mcpWriteToolsEnabled = true;
        loaded.mcpAllowedTools = java.util.Arrays.asList(
                "statement.list", null, "bad tool", "statement.list", "mapper.list");
        loaded.uiLocale = "unknown";

        MyBatisAssistantSettings.getInstance().loadState(loaded);

        assertFalse(MyBatisAssistantSettings.getInstance().isShowNotifications());
        assertTrue(MyBatisAssistantSettings.getInstance().isNetworkAccessAllowed());
        assertTrue(MyBatisAssistantSettings.getInstance().isExperimentalFeaturesEnabled());
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpEnabled());
        assertEquals(0, MyBatisAssistantSettings.getInstance().getMcpPort());
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpWriteToolsEnabled());
        assertEquals(java.util.List.of("mapper.list", "statement.list"),
                MyBatisAssistantSettings.getInstance().getMcpAllowedTools());
        assertEquals("", MyBatisAssistantSettings.getInstance().getUiLocale());
        assertEquals(2, MyBatisAssistantSettings.getInstance().getState().schemaVersion);
        assertNotSame(loaded, MyBatisAssistantSettings.getInstance().getState());
    }

    public void testLegacyUpdatePreservesNewSettingsAndStateIsDefensiveCopy() {
        MyBatisAssistantSettings.SettingsState state = new MyBatisAssistantSettings.SettingsState();
        state.mcpEnabled = true;
        state.mcpPort = 61234;
        state.mcpWriteToolsEnabled = true;
        state.mcpAllowedTools = java.util.List.of("mapper.list");
        state.uiLocale = "en";
        MyBatisAssistantSettings.getInstance().replace(state);

        MyBatisAssistantSettings.getInstance().update(false, true, true);

        assertTrue(MyBatisAssistantSettings.getInstance().isMcpEnabled());
        assertEquals(61234, MyBatisAssistantSettings.getInstance().getMcpPort());
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpWriteToolsEnabled());
        assertEquals(java.util.List.of("mapper.list"),
                MyBatisAssistantSettings.getInstance().getMcpAllowedTools());
        assertEquals("en", MyBatisAssistantSettings.getInstance().getUiLocale());
        MyBatisAssistantSettings.SettingsState copy = MyBatisAssistantSettings.getInstance().getState();
        copy.mcpEnabled = false;
        copy.mcpAllowedTools.clear();
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpEnabled());
        assertEquals(java.util.List.of("mapper.list"),
                MyBatisAssistantSettings.getInstance().getMcpAllowedTools());
    }

    public void testRegisteredConfigurableAppliesAndResetsSettings()
            throws ConfigurationException {
        var extension = Configurable.APPLICATION_CONFIGURABLE.getExtensionList().stream()
                .filter(candidate -> MyBatisAssistantSettingsConfigurable.ID.equals(candidate.id))
                .findFirst()
                .orElseThrow();
        assertEquals("messages.MyBatisAssistantBundle", extension.bundle);
        assertEquals("settings.display.name", extension.key);

        var created = extension.createConfigurable();
        assertEquals(MyBatisAssistantSettingsConfigurable.class, created.getClass());
        var configurable = (MyBatisAssistantSettingsConfigurable) created;
        assertNotNull(configurable.createComponent());
        assertFalse(configurable.isModified());

        MyBatisAssistantSettingsPanel panel = configurable.getSettingsPanel();
        panel.setShowNotifications(false);
        panel.setAllowNetworkAccess(true);
        panel.setExperimentalFeatures(true);
        assertTrue(configurable.isModified());

        configurable.apply();
        assertFalse(configurable.isModified());
        assertFalse(MyBatisAssistantSettings.getInstance().isShowNotifications());
        assertTrue(MyBatisAssistantSettings.getInstance().isNetworkAccessAllowed());
        assertTrue(MyBatisAssistantSettings.getInstance().isExperimentalFeaturesEnabled());

        MyBatisAssistantSettings.getInstance().update(true, false, false);
        configurable.reset();
        assertFalse(configurable.isModified());
        configurable.disposeUIResources();
    }

    public void testSettingsPanelImportsExportsAndRestoresConservativeDefaults()
            throws ConfigurationException {
        MyBatisAssistantSettingsConfigurable configurable =
                new MyBatisAssistantSettingsConfigurable();
        assertNotNull(configurable.createComponent());
        MyBatisAssistantSettingsPanel panel = configurable.getSettingsPanel();

        panel.importText("""
                schemaVersion=2
                showNotifications=false
                allowNetworkAccess=true
                experimentalFeatures=true
                mcpEnabled=true
                mcpPort=61234
                mcpWriteToolsEnabled=true
                mcpAllowedTools=statement.list,mapper.list
                uiLocale=en
                """);
        assertTrue(configurable.isModified());
        assertEquals(panel.exportText(), MyBatisAssistantSettingsCodec.encode(
                MyBatisAssistantSettingsCodec.decode(panel.exportText())));

        configurable.apply();
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpEnabled());
        assertEquals(61234, MyBatisAssistantSettings.getInstance().getMcpPort());
        assertTrue(MyBatisAssistantSettings.getInstance().isMcpWriteToolsEnabled());
        assertEquals(java.util.List.of("mapper.list", "statement.list"),
                MyBatisAssistantSettings.getInstance().getMcpAllowedTools());
        assertEquals("en", MyBatisAssistantSettings.getInstance().getUiLocale());

        panel.restoreDefaults();
        assertTrue(configurable.isModified());
        configurable.apply();
        assertFalse(MyBatisAssistantSettings.getInstance().isMcpEnabled());
        assertEquals(0, MyBatisAssistantSettings.getInstance().getMcpPort());
        assertFalse(MyBatisAssistantSettings.getInstance().isMcpWriteToolsEnabled());
        assertEquals(MyBatisAssistantSettings.DEFAULT_MCP_ALLOWED_TOOLS,
                MyBatisAssistantSettings.getInstance().getMcpAllowedTools());
        configurable.disposeUIResources();
    }

    public void testTransferDialogsValidateBeforeImportAndKeepExportReadOnly() {
        MyBatisAssistantSettingsTransferDialog importDialog =
                MyBatisAssistantSettingsTransferDialog.importDialog();
        assertNotNull(importDialog.createCenterPanel());
        assertThrows(IllegalStateException.class, importDialog::importedState);
        importDialog.setContent("schemaVersion=2\nmcpPort=80\n");
        assertNotNull(importDialog.doValidate());
        importDialog.setContent("schemaVersion=2\nmcpPort=61234\nuiLocale=en\n");
        assertNull(importDialog.doValidate());
        importDialog.doOKAction();
        assertEquals(61234, importDialog.importedState().mcpPort);
        assertEquals("en", importDialog.importedState().uiLocale);

        MyBatisAssistantSettingsTransferDialog exportDialog =
                MyBatisAssistantSettingsTransferDialog.exportDialog("schemaVersion=2\n");
        assertNotNull(exportDialog.createCenterPanel());
        assertNull(exportDialog.doValidate());
        assertEquals(1, exportDialog.createActions().length);
        assertThrows(IllegalStateException.class, exportDialog::importedState);
    }

    public void testOptionalDependencyDescriptorsAreDeclared() throws IOException {
        try (var input = getClass().getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull("测试运行时缺少 plugin.xml", input);
            String pluginXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(pluginXml.contains("org.jetbrains.kotlin"));
            assertTrue(pluginXml.contains("com.intellij.spring"));
            assertTrue(pluginXml.contains("org.jetbrains.plugins.yaml"));
            assertTrue(pluginXml.contains("com.intellij.database"));
            assertTrue(pluginXml.contains("optional=\"true\""));
        }
    }

    public void testCoreDescriptorKeepsAndroidAndCommunityPathsFreeOfOptionalApis()
            throws IOException {
        String core = resource("/META-INF/plugin.xml");
        String database = resource(
                "/META-INF/io.github.ns3154.mybatisassistant-withDatabase.xml");
        String spring = resource(
                "/META-INF/io.github.ns3154.mybatisassistant-withSpring.xml");

        assertTrue(core.contains("com.intellij.modules.platform"));
        assertTrue(core.contains("com.intellij.java"));
        assertTrue(core.contains("com.intellij.modules.xml"));
        assertTrue(core.contains("MyBatisJdbcMetadataProvider"));
        assertTrue(core.contains("MyBatisJdbcDataSourcesConfigurable"));
        assertFalse(core.contains("database.intellij."));
        assertFalse(core.contains("spring.MyBatisSpring"));
        assertTrue(database.contains("database.intellij."));
        assertTrue(spring.contains("spring.MyBatisSpring"));
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull("测试运行时缺少资源：" + path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
