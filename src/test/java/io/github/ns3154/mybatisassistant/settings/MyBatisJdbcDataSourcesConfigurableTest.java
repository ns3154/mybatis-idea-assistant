package io.github.ns3154.mybatisassistant.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcCredentialStore;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceConfig;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class MyBatisJdbcDataSourcesConfigurableTest extends BasePlatformTestCase {
    private MyBatisJdbcDataSourceManager.SettingsState originalState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalState = manager().getState();
        manager().loadState(new MyBatisJdbcDataSourceManager.SettingsState());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            manager().loadState(originalState);
        } finally {
            super.tearDown();
        }
    }

    public void testProjectConfigurableIsRegisteredAndAppliesNonSensitiveSource() {
        var extension = Configurable.PROJECT_CONFIGURABLE.getExtensions(getProject()).stream()
                .filter(candidate -> MyBatisJdbcDataSourcesConfigurable.ID.equals(candidate.id))
                .findFirst()
                .orElseThrow();
        assertEquals("messages.MyBatisAssistantBundle", extension.bundle);
        assertEquals("settings.jdbc.display.name", extension.key);

        var configurable = assertInstanceOf(
                extension.createConfigurable(),
                MyBatisJdbcDataSourcesConfigurable.class);
        assertNotNull(configurable.createComponent());
        assertFalse(configurable.isModified());
        MyBatisJdbcDataSourceConfig config = new MyBatisJdbcDataSourceConfig(
                "community",
                "Community",
                MyBatisSqlDialect.DAMENG,
                "jdbc:dm://localhost:5236",
                "dm.jdbc.driver.DmDriver",
                List.of(),
                "SYSDBA",
                false,
                Optional.empty(),
                Optional.of("SYSDBA"),
                true);
        configurable.panel().replaceForTest(List.of(config), null);

        assertTrue(configurable.isModified());
        configurable.apply();
        assertFalse(configurable.isModified());
        assertEquals(List.of(config), manager().dataSources());

        manager().replace(List.of());
        configurable.reset();
        assertFalse(configurable.isModified());
        configurable.disposeUIResources();
    }

    public void testDisablingPasswordRequirementRemovesStoredCredential() throws Exception {
        MyBatisJdbcDataSourceConfig secured = config(true);
        manager().replace(List.of(secured));
        MyBatisJdbcCredentialStore.storePassword(
                getProject(), secured.id(), secured.username(), "secret".toCharArray())
                .get(5, TimeUnit.SECONDS);
        MyBatisJdbcDataSourcesConfigurable configurable =
                new MyBatisJdbcDataSourcesConfigurable(getProject());
        try {
            assertNotNull(configurable.createComponent());
            configurable.panel().replaceForTest(List.of(config(false)), null);

            configurable.apply();

            Optional<String> password = com.intellij.util.concurrency.AppExecutorUtil
                    .getAppExecutorService()
                    .submit(() -> MyBatisJdbcCredentialStore.password(
                            getProject(), secured.id(), secured.username()))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(password.isEmpty());
        } finally {
            configurable.disposeUIResources();
            MyBatisJdbcCredentialStore.storePassword(
                    getProject(), secured.id(), secured.username(), null)
                    .get(5, TimeUnit.SECONDS);
        }
    }

    private MyBatisJdbcDataSourceManager manager() {
        return MyBatisJdbcDataSourceManager.getInstance(getProject());
    }

    private static MyBatisJdbcDataSourceConfig config(boolean passwordRequired) {
        return new MyBatisJdbcDataSourceConfig(
                "community",
                "Community",
                MyBatisSqlDialect.DAMENG,
                "jdbc:dm://localhost:5236",
                "dm.jdbc.driver.DmDriver",
                List.of(),
                "SYSDBA",
                passwordRequired,
                Optional.empty(),
                Optional.of("SYSDBA"),
                true);
    }
}
