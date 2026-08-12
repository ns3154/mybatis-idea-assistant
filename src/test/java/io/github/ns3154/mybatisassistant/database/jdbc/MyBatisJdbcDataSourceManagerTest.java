package io.github.ns3154.mybatisassistant.database.jdbc;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MyBatisJdbcDataSourceManagerTest extends BasePlatformTestCase {
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

    public void testValidatesExplicitDialectDriverAndAbsoluteJarPaths() {
        expectIllegalArgument(() -> config("main", MyBatisSqlDialect.GENERIC, List.of()));
        expectIllegalArgument(() -> new MyBatisJdbcDataSourceConfig(
                "main", "Main", MyBatisSqlDialect.MYSQL, "https://localhost/db",
                "org.example.Driver", List.of(), "user", false,
                Optional.empty(), Optional.empty(), true));
        expectIllegalArgument(() -> new MyBatisJdbcDataSourceConfig(
                "main", "Main", MyBatisSqlDialect.MYSQL,
                "jdbc:mysql://user:secret@localhost/db",
                "org.example.Driver", List.of(), "user", true,
                Optional.empty(), Optional.empty(), true));
        expectIllegalArgument(() -> new MyBatisJdbcDataSourceConfig(
                "main", "Main", MyBatisSqlDialect.SQL_SERVER,
                "jdbc:sqlserver://localhost;user=sa;password=secret",
                "org.example.Driver", List.of(), "sa", true,
                Optional.empty(), Optional.empty(), true));
        expectIllegalArgument(() -> new MyBatisJdbcDataSourceConfig(
                "main", "Main", MyBatisSqlDialect.ORACLE,
                "jdbc:oracle:thin:scott/tiger@localhost:1521/FREEPDB1",
                "org.example.Driver", List.of(), "scott", true,
                Optional.empty(), Optional.empty(), true));
        expectIllegalArgument(() -> new MyBatisJdbcDataSourceConfig(
                "main", "Main", MyBatisSqlDialect.MYSQL, "jdbc:mysql://localhost/db",
                "Driver", List.of(), "user", false,
                Optional.empty(), Optional.empty(), true));
        expectIllegalArgument(() -> config(
                "main", MyBatisSqlDialect.MYSQL, List.of("driver.jar")));
        expectIllegalArgument(() -> config(
                "main", MyBatisSqlDialect.MYSQL, List.of("/tmp/driver.zip")));

        MyBatisJdbcDataSourceConfig config = new MyBatisJdbcDataSourceConfig(
                " main ", " Main ", MyBatisSqlDialect.DAMENG, "jdbc:dm://localhost:5236",
                "dm.jdbc.driver.DmDriver",
                List.of("/tmp/dm.jar", "/tmp/./dm.jar"),
                " SYSDBA ", true,
                Optional.of(" "), Optional.of(" SYSDBA "), true);

        assertEquals("main", config.id());
        assertEquals("Main", config.displayName());
        assertEquals(List.of("/tmp/dm.jar"), config.driverJarPaths());
        assertEquals("SYSDBA", config.username());
        assertTrue(config.catalog().isEmpty());
        assertEquals(Optional.of("SYSDBA"), config.schema());
    }

    public void testPersistsOnlyNonSensitiveConfigurationAndSortsDeterministically() {
        List<MyBatisJdbcDataSourceConfig> mutable = new ArrayList<>();
        mutable.add(config("second", MyBatisSqlDialect.POSTGRESQL, List.of()));
        mutable.add(config("first", MyBatisSqlDialect.MYSQL, List.of()));

        manager().replace(mutable);
        mutable.clear();

        assertEquals(List.of("first", "second"), manager().dataSources().stream()
                .map(MyBatisJdbcDataSourceConfig::id)
                .toList());
        MyBatisJdbcDataSourceManager.SettingsState state = manager().getState();
        assertEquals(1, state.schemaVersion);
        assertEquals(2, state.sources.size());
        assertFalse(state.sources.getFirst().getClass().getDeclaredFields().length == 0);
        assertFalse(java.util.Arrays.stream(state.sources.getFirst().getClass().getDeclaredFields())
                .anyMatch(field -> "password".equalsIgnoreCase(field.getName())));
        expectIllegalArgument(() -> manager().replace(List.of(
                config("same", MyBatisSqlDialect.MYSQL, List.of()),
                config("same", MyBatisSqlDialect.POSTGRESQL, List.of()))));
    }

    public void testCorruptPersistentEntriesAreDroppedWithoutBreakingValidSources() {
        MyBatisJdbcDataSourceManager.SettingsState state = new MyBatisJdbcDataSourceManager.SettingsState();
        MyBatisJdbcDataSourceManager.SourceState broken = new MyBatisJdbcDataSourceManager.SourceState();
        broken.id = "broken";
        broken.displayName = "Broken";
        broken.dialect = "UNKNOWN_DIALECT";
        state.sources.add(broken);
        state.sources.add(MyBatisJdbcDataSourceManager.SourceState.from(
                config("valid", MyBatisSqlDialect.H2, List.of())));

        manager().loadState(state);

        assertEquals(List.of("valid"), manager().dataSources().stream()
                .map(MyBatisJdbcDataSourceConfig::id)
                .toList());
    }

    private MyBatisJdbcDataSourceManager manager() {
        return MyBatisJdbcDataSourceManager.getInstance(getProject());
    }

    private static MyBatisJdbcDataSourceConfig config(
            String id,
            MyBatisSqlDialect dialect,
            List<String> paths) {
        return new MyBatisJdbcDataSourceConfig(
                id,
                id,
                dialect,
                "jdbc:h2:mem:" + id,
                "org.h2.Driver",
                paths,
                "sa",
                false,
                Optional.empty(),
                Optional.of("PUBLIC"),
                true);
    }

    private static void expectIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期分支。
        }
    }
}
