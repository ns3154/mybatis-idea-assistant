package io.github.ns3154.mybatisassistant.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 不依赖 Database Tools 的稳定 SQL 方言标识。
 */
public enum MyBatisSqlDialect {
    GENERIC,
    MYSQL,
    POSTGRESQL,
    ORACLE,
    SQL_SERVER,
    SQLITE,
    H2;

    public static @NotNull MyBatisSqlDialect fromDatabaseId(@Nullable String databaseId) {
        if (databaseId == null || databaseId.isBlank()) {
            return GENERIC;
        }
        String normalized = databaseId.trim().toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        return switch (normalized) {
            case "mysql", "mariadb", "maria" -> MYSQL;
            case "postgres", "postgresql", "pgsql" -> POSTGRESQL;
            case "oracle" -> ORACLE;
            case "sqlserver", "mssql", "tsql" -> SQL_SERVER;
            case "sqlite" -> SQLITE;
            case "h2" -> H2;
            default -> GENERIC;
        };
    }
}
