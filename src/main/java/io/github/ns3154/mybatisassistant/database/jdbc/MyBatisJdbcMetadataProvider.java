package io.github.ns3154.mybatisassistant.database.jdbc;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMessages;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseObjectKind;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisForeignKeyReference;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 无 Database Tools 时使用用户显式配置驱动的 Community JDBC 元数据提供方。
 */
public final class MyBatisJdbcMetadataProvider implements MyBatisDatabaseMetadataProvider {
    @Override
    public @NotNull String id() {
        return "community-jdbc";
    }

    @Override
    public @NotNull List<MyBatisDatabaseSnapshot> load(
            @NotNull Project project,
            @NotNull MyBatisDatabaseRequest request,
            @NotNull ProgressIndicator indicator) {
        if (project.isDisposed() || !project.isOpen()) {
            return List.of();
        }
        List<MyBatisJdbcDataSourceConfig> configurations = MyBatisJdbcDataSourceManager
                .getInstance(project)
                .dataSources();
        List<MyBatisDatabaseSnapshot> snapshots = new ArrayList<>();
        boolean attempted = false;
        RuntimeException lastFailure = null;
        for (MyBatisJdbcDataSourceConfig config : configurations) {
            indicator.checkCanceled();
            if (!config.enabled() || request.dataSourceId().isPresent()
                    && !request.dataSourceId().orElseThrow().equals(config.id())) {
                continue;
            }
            attempted = true;
            try {
                Optional<String> password = config.passwordRequired()
                        ? MyBatisJdbcCredentialStore.password(
                                project, config.id(), config.username())
                        : Optional.of("");
                if (password.isEmpty()) {
                    continue;
                }
                snapshots.add(loadSnapshot(config, password.orElseThrow(), indicator));
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        if (snapshots.isEmpty() && attempted && lastFailure != null) {
            throw lastFailure;
        }
        return List.copyOf(snapshots);
    }

    static @NotNull MyBatisDatabaseSnapshot loadSnapshot(
            @NotNull MyBatisJdbcDataSourceConfig config,
            @NotNull String password,
            @NotNull ProgressIndicator indicator) {
        try (DriverHandle handle = loadDriver(config);
             Connection connection = connect(handle.driver, config, password)) {
            indicator.checkCanceled();
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // 部分 JDBC 驱动不支持只读提示；本提供方仍只调用 DatabaseMetaData。
            }
            List<MyBatisDatabaseTable> tables = tables(connection, config, indicator);
            return new MyBatisDatabaseSnapshot(
                    config.id(),
                    config.displayName(),
                    config.dialect(),
                    MyBatisMetadataFreshness.READY,
                    System.nanoTime() & Long.MAX_VALUE,
                    tables);
        } catch (SQLException | IOException | ReflectiveOperationException failure) {
            throw new IllegalStateException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.metadata.read",
                    config.displayName()),
                    failure);
        }
    }

    private static @NotNull Connection connect(
            @NotNull Driver driver,
            @NotNull MyBatisJdbcDataSourceConfig config,
            @NotNull String password) throws SQLException {
        Properties properties = new Properties();
        if (!config.username().isEmpty()) {
            properties.setProperty("user", config.username());
        }
        if (config.passwordRequired()) {
            properties.setProperty("password", password);
        }
        Connection connection = driver.connect(config.jdbcUrl(), properties);
        if (connection == null) {
            throw new SQLException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.driver.rejects.url"));
        }
        return connection;
    }

    private static @NotNull DriverHandle loadDriver(@NotNull MyBatisJdbcDataSourceConfig config)
            throws IOException, ReflectiveOperationException {
        if (config.driverJarPaths().isEmpty()) {
            Class<?> driverClass = Class.forName(config.driverClassName());
            return new DriverHandle(asDriver(driverClass), null);
        }
        List<URL> urls = new ArrayList<>();
        for (String driverJarPath : config.driverJarPaths()) {
            Path path = Path.of(driverJarPath);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IOException(MyBatisDatabaseMessages.message(
                        "database.error.jdbc.driver.jar.unreadable"));
            }
            urls.add(path.toUri().toURL());
        }
        URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
        try {
            Class<?> driverClass = Class.forName(config.driverClassName(), true, classLoader);
            return new DriverHandle(asDriver(driverClass), classLoader);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            classLoader.close();
            throw failure;
        }
    }

    private static @NotNull Driver asDriver(@NotNull Class<?> driverClass)
            throws ReflectiveOperationException {
        Object instance = driverClass.getDeclaredConstructor().newInstance();
        if (!(instance instanceof Driver driver)) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.driver.interface"));
        }
        return driver;
    }

    private static @NotNull List<MyBatisDatabaseTable> tables(
            @NotNull Connection connection,
            @NotNull MyBatisJdbcDataSourceConfig config,
            @NotNull ProgressIndicator indicator) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = config.catalog().orElse(null);
        String schema = config.schema().orElse(null);
        List<MyBatisDatabaseTable> tables = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rows.next()) {
                indicator.checkCanceled();
                String tableCatalog = rows.getString("TABLE_CAT");
                String tableSchema = rows.getString("TABLE_SCHEM");
                String tableName = rows.getString("TABLE_NAME");
                if (tableName == null || tableName.isBlank()) {
                    continue;
                }
                MyBatisDatabaseObjectKind kind = "VIEW".equalsIgnoreCase(
                        rows.getString("TABLE_TYPE"))
                        ? MyBatisDatabaseObjectKind.VIEW
                        : MyBatisDatabaseObjectKind.TABLE;
                tables.add(table(
                        metadata,
                        tableCatalog,
                        tableSchema,
                        tableName,
                        optional(rows.getString("REMARKS")),
                        kind,
                        indicator));
            }
        }
        tables.sort(Comparator
                .comparing((MyBatisDatabaseTable table) -> table.catalog().orElse(""))
                .thenComparing(table -> table.schema().orElse(""))
                .thenComparing(MyBatisDatabaseTable::name));
        return List.copyOf(tables);
    }

    private static @NotNull MyBatisDatabaseTable table(
            @NotNull DatabaseMetaData metadata,
            String catalog,
            String schema,
            @NotNull String tableName,
            @NotNull Optional<String> comment,
            @NotNull MyBatisDatabaseObjectKind kind,
            @NotNull ProgressIndicator indicator) throws SQLException {
        Set<String> primaryKeys = names(
                () -> metadata.getPrimaryKeys(catalog, schema, tableName),
                "COLUMN_NAME",
                indicator);
        ImportedForeignKeys foreignKeys = importedForeignKeys(
                metadata, catalog, schema, tableName, indicator);
        List<MyBatisDatabaseColumn> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, tableName, "%")) {
            while (rows.next()) {
                indicator.checkCanceled();
                String name = rows.getString("COLUMN_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int position = Math.max(0, rows.getInt("ORDINAL_POSITION") - 1);
                columns.add(new MyBatisDatabaseColumn(
                        name,
                        Optional.ofNullable(rows.getString("TYPE_NAME")).orElse("UNKNOWN"),
                        rows.getInt("DATA_TYPE"),
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        primaryKeys.contains(name),
                        foreignKeys.columns().contains(name),
                        "YES".equalsIgnoreCase(safeString(rows, "IS_AUTOINCREMENT")),
                        "YES".equalsIgnoreCase(safeString(rows, "IS_GENERATEDCOLUMN")),
                        optional(rows.getString("REMARKS")),
                        position,
                        foreignKeys.reference(name)));
            }
        }
        columns.sort(Comparator.comparingInt(MyBatisDatabaseColumn::position)
                .thenComparing(MyBatisDatabaseColumn::name));
        return new MyBatisDatabaseTable(
                optional(catalog), optional(schema), tableName, comment, kind, columns);
    }

    private static @NotNull Set<String> names(
            @NotNull ResultSetSupplier rowsSupplier,
            @NotNull String column,
            @NotNull ProgressIndicator indicator) {
        try (ResultSet rows = rowsSupplier.get()) {
            Set<String> names = new HashSet<>();
            while (rows.next()) {
                indicator.checkCanceled();
                String name = rows.getString(column);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (SQLException unsupportedKeys) {
            return Set.of();
        }
    }

    private static @NotNull ImportedForeignKeys importedForeignKeys(
            @NotNull DatabaseMetaData metadata,
            String catalog,
            String schema,
            @NotNull String tableName,
            @NotNull ProgressIndicator indicator) {
        Set<String> columns = new HashSet<>();
        List<ImportedKeyRow> importedRows = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, tableName)) {
            while (rows.next()) {
                indicator.checkCanceled();
                String foreignColumn = safeString(rows, "FKCOLUMN_NAME");
                if (foreignColumn == null || foreignColumn.isBlank()) {
                    continue;
                }
                columns.add(foreignColumn);
                String primaryTable = safeString(rows, "PKTABLE_NAME");
                String primaryColumn = safeString(rows, "PKCOLUMN_NAME");
                if (primaryTable == null || primaryTable.isBlank()
                        || primaryColumn == null || primaryColumn.isBlank()) {
                    continue;
                }
                String constraintIdentity = safeString(rows, "FK_NAME");
                int ordinal = safeInt(rows, "KEY_SEQ", 0);
                if (ordinal < 1) {
                    continue;
                }
                importedRows.add(new ImportedKeyRow(
                        foreignColumn,
                        optional(safeString(rows, "PKTABLE_CAT")),
                        optional(safeString(rows, "PKTABLE_SCHEM")),
                        primaryTable,
                        primaryColumn,
                        optional(constraintIdentity),
                        ordinal));
            }
        } catch (SQLException unsupportedKeys) {
            return ImportedForeignKeys.empty();
        }
        Map<ImportedConstraintIdentity, Integer> columnCounts = new HashMap<>();
        for (ImportedKeyRow row : importedRows) {
            ImportedConstraintIdentity identity = row.constraintIdentity();
            columnCounts.merge(identity, row.ordinal(), Math::max);
        }
        Map<String, Set<MyBatisForeignKeyReference>> candidates = new HashMap<>();
        for (ImportedKeyRow row : importedRows) {
            int columnCount = columnCounts.getOrDefault(row.constraintIdentity(), row.ordinal());
            MyBatisForeignKeyReference reference = new MyBatisForeignKeyReference(
                    row.catalog(), row.schema(), row.table(), row.primaryColumn(),
                    row.constraintName(), row.ordinal(), columnCount);
            candidates.computeIfAbsent(row.foreignColumn(), ignored -> new HashSet<>())
                    .add(reference);
        }
        Map<String, Set<MyBatisForeignKeyReference>> immutableCandidates = new HashMap<>();
        candidates.forEach((name, references) ->
                immutableCandidates.put(name, Set.copyOf(references)));
        return new ImportedForeignKeys(Set.copyOf(columns), Map.copyOf(immutableCandidates));
    }

    private static String safeString(@NotNull ResultSet rows, @NotNull String column) {
        try {
            return rows.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static int safeInt(
            @NotNull ResultSet rows,
            @NotNull String column,
            int fallback) {
        try {
            int value = rows.getInt(column);
            return rows.wasNull() ? fallback : value;
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private static @NotNull Optional<String> optional(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty());
    }

    private record DriverHandle(@NotNull Driver driver, URLClassLoader classLoader)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (classLoader != null) {
                classLoader.close();
            }
        }
    }

    private record ImportedForeignKeys(
            @NotNull Set<String> columns,
            @NotNull Map<String, Set<MyBatisForeignKeyReference>> candidates) {
        private static @NotNull ImportedForeignKeys empty() {
            return new ImportedForeignKeys(Set.of(), Map.of());
        }

        private @NotNull Optional<MyBatisForeignKeyReference> reference(
                @NotNull String column) {
            Set<MyBatisForeignKeyReference> references = candidates.getOrDefault(
                    column, Set.of());
            return references.size() == 1
                    ? Optional.of(references.iterator().next())
                    : Optional.empty();
        }
    }

    private record ImportedKeyRow(
            @NotNull String foreignColumn,
            @NotNull Optional<String> catalog,
            @NotNull Optional<String> schema,
            @NotNull String table,
            @NotNull String primaryColumn,
            @NotNull Optional<String> constraintName,
            int ordinal) {
        private @NotNull ImportedConstraintIdentity constraintIdentity() {
            return new ImportedConstraintIdentity(
                    constraintName, catalog, schema, table);
        }
    }

    private record ImportedConstraintIdentity(
            @NotNull Optional<String> constraintName,
            @NotNull Optional<String> catalog,
            @NotNull Optional<String> schema,
            @NotNull String table) {
    }

    @FunctionalInterface
    private interface ResultSetSupplier {
        @NotNull ResultSet get() throws SQLException;
    }
}
