package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.options.Configurable;
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
        assertEquals(1, settings.getState().schemaVersion);
    }

    public void testLoadsAndNormalizesPersistentState() {
        MyBatisAssistantSettings.SettingsState loaded = new MyBatisAssistantSettings.SettingsState();
        loaded.schemaVersion = -10;
        loaded.showNotifications = false;
        loaded.allowNetworkAccess = true;
        loaded.experimentalFeatures = true;

        MyBatisAssistantSettings.getInstance().loadState(loaded);

        assertFalse(MyBatisAssistantSettings.getInstance().isShowNotifications());
        assertTrue(MyBatisAssistantSettings.getInstance().isNetworkAccessAllowed());
        assertTrue(MyBatisAssistantSettings.getInstance().isExperimentalFeaturesEnabled());
        assertEquals(1, MyBatisAssistantSettings.getInstance().getState().schemaVersion);
        assertNotSame(loaded, MyBatisAssistantSettings.getInstance().getState());
    }

    public void testRegisteredConfigurableAppliesAndResetsSettings() {
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
