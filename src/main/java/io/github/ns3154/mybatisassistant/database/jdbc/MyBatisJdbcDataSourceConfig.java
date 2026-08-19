package io.github.ns3154.mybatisassistant.database.jdbc;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMessages;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Community 环境可用的项目级 JDBC 数据源配置；密码不属于本模型。
 */
public record MyBatisJdbcDataSourceConfig(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull MyBatisSqlDialect dialect,
        @NotNull String jdbcUrl,
        @NotNull String driverClassName,
        @NotNull List<String> driverJarPaths,
        @NotNull String username,
        boolean passwordRequired,
        @NotNull Optional<String> catalog,
        @NotNull Optional<String> schema,
        boolean enabled) {
    private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
            "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                    + "(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");
    private static final Pattern URL_CREDENTIAL_PROPERTY = Pattern.compile(
            "(?i)(?:[?;&]|\\b)(?:user|username|password|passwd|pwd)\\s*=");
    private static final Pattern URL_AUTHORITY_USER_INFO = Pattern.compile(
            "(?i)^jdbc:[^:]+://[^/?#]*@");
    private static final Pattern ORACLE_THIN_USER_INFO = Pattern.compile(
            "(?i)^jdbc:oracle:thin:(?!@)[^@]+@");

    public MyBatisJdbcDataSourceConfig {
        id = requireText(id, MyBatisDatabaseMessages.message(
                "database.error.jdbc.id.empty"));
        displayName = requireText(displayName, MyBatisDatabaseMessages.message(
                "database.error.jdbc.name.empty"));
        jdbcUrl = requireText(jdbcUrl, MyBatisDatabaseMessages.message(
                "database.error.jdbc.url.empty"));
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.url.prefix"));
        }
        if (URL_CREDENTIAL_PROPERTY.matcher(jdbcUrl).find()
                || URL_AUTHORITY_USER_INFO.matcher(jdbcUrl).find()
                || ORACLE_THIN_USER_INFO.matcher(jdbcUrl).find()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.url.credentials"));
        }
        driverClassName = requireText(driverClassName, MyBatisDatabaseMessages.message(
                "database.error.jdbc.driver.class.empty"));
        if (!JAVA_CLASS_NAME.matcher(driverClassName).matches()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.driver.class.invalid"));
        }
        if (dialect == MyBatisSqlDialect.GENERIC) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.dialect.required"));
        }
        driverJarPaths = driverJarPaths.stream()
                .map(path -> normalizeDriverPath(path))
                .distinct()
                .toList();
        username = username == null ? "" : username.trim();
        catalog = normalizeOptional(catalog);
        schema = normalizeOptional(schema);
    }

    private static @NotNull String requireText(@NotNull String value, @NotNull String message) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static @NotNull String normalizeDriverPath(@NotNull String value) {
        try {
            Path path = Path.of(requireText(value, MyBatisDatabaseMessages.message(
                    "database.error.jdbc.driver.path.empty"))).normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                        "database.error.jdbc.driver.path.absolute"));
            }
            if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".jar")) {
                throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                        "database.error.jdbc.driver.path.jar"));
            }
            return path.toString();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.jdbc.driver.path.invalid"), failure);
        }
    }

    private static @NotNull Optional<String> normalizeOptional(
            @NotNull Optional<String> value) {
        return value.map(String::trim).filter(text -> !text.isEmpty());
    }
}
