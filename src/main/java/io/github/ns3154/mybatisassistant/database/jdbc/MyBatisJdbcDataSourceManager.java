package io.github.ns3154.mybatisassistant.database.jdbc;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMessages;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 保存项目私有的 Community JDBC 非敏感配置。
 */
@State(
        name = "io.github.ns3154.mybatisassistant.jdbcDataSources",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class MyBatisJdbcDataSourceManager
        implements PersistentStateComponent<MyBatisJdbcDataSourceManager.SettingsState> {
    private final Project project;
    private volatile SettingsState state = new SettingsState();

    public MyBatisJdbcDataSourceManager(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull MyBatisJdbcDataSourceManager getInstance(@NotNull Project project) {
        return project.getService(MyBatisJdbcDataSourceManager.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state.copyAndNormalize();
    }

    @Override
    public void loadState(@NotNull SettingsState loadedState) {
        state = loadedState.copyAndNormalize();
        invalidateMetadata();
    }

    public @NotNull List<MyBatisJdbcDataSourceConfig> dataSources() {
        return state.sources.stream()
                .map(SourceState::toConfig)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(MyBatisJdbcDataSourceConfig::displayName)
                        .thenComparing(MyBatisJdbcDataSourceConfig::id))
                .toList();
    }

    public void replace(@NotNull List<MyBatisJdbcDataSourceConfig> dataSources) {
        Set<String> ids = new HashSet<>();
        SettingsState updated = new SettingsState();
        for (MyBatisJdbcDataSourceConfig dataSource : dataSources) {
            if (!ids.add(dataSource.id())) {
                throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                        "database.error.jdbc.id.duplicate",
                        dataSource.id()));
            }
            updated.sources.add(SourceState.from(dataSource));
        }
        state = updated.copyAndNormalize();
        invalidateMetadata();
    }

    private void invalidateMetadata() {
        if (!project.isDisposed()) {
            MyBatisDatabaseMetadataService.getInstance(project).invalidate();
        }
    }

    public static final class SettingsState {
        public int schemaVersion = 1;
        public List<SourceState> sources = new ArrayList<>();

        public @NotNull SettingsState copyAndNormalize() {
            SettingsState copy = new SettingsState();
            Set<String> ids = new HashSet<>();
            if (sources == null) {
                return copy;
            }
            for (SourceState source : sources) {
                if (source == null) {
                    continue;
                }
                source.toConfig().ifPresent(config -> {
                    if (ids.add(config.id())) {
                        copy.sources.add(SourceState.from(config));
                    }
                });
            }
            copy.sources.sort(Comparator.comparing(value -> value.displayName));
            return copy;
        }
    }

    public static final class SourceState {
        public String id = "";
        public String displayName = "";
        public String dialect = "GENERIC";
        public String jdbcUrl = "";
        public String driverClassName = "";
        public List<String> driverJarPaths = new ArrayList<>();
        public String username = "";
        public boolean passwordRequired = true;
        public String catalog = "";
        public String schema = "";
        public boolean enabled = true;

        static @NotNull SourceState from(@NotNull MyBatisJdbcDataSourceConfig config) {
            SourceState state = new SourceState();
            state.id = config.id();
            state.displayName = config.displayName();
            state.dialect = config.dialect().name();
            state.jdbcUrl = config.jdbcUrl();
            state.driverClassName = config.driverClassName();
            state.driverJarPaths = new ArrayList<>(config.driverJarPaths());
            state.username = config.username();
            state.passwordRequired = config.passwordRequired();
            state.catalog = config.catalog().orElse("");
            state.schema = config.schema().orElse("");
            state.enabled = config.enabled();
            return state;
        }

        @NotNull Optional<MyBatisJdbcDataSourceConfig> toConfig() {
            try {
                return Optional.of(new MyBatisJdbcDataSourceConfig(
                        id,
                        displayName,
                        MyBatisSqlDialect.valueOf(dialect),
                        jdbcUrl,
                        driverClassName,
                        driverJarPaths == null ? List.of() : driverJarPaths,
                        username,
                        passwordRequired,
                        Optional.ofNullable(catalog),
                        Optional.ofNullable(schema),
                        enabled));
            } catch (IllegalArgumentException | NullPointerException failure) {
                return Optional.empty();
            }
        }
    }
}
