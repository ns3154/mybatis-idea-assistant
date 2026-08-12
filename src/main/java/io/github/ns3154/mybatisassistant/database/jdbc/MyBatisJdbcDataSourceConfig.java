package io.github.ns3154.mybatisassistant.database.jdbc;

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
        id = requireText(id, "数据源标识不能为空");
        displayName = requireText(displayName, "数据源名称不能为空");
        jdbcUrl = requireText(jdbcUrl, "JDBC URL 不能为空");
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("JDBC URL 必须以 jdbc: 开头");
        }
        if (URL_CREDENTIAL_PROPERTY.matcher(jdbcUrl).find()
                || URL_AUTHORITY_USER_INFO.matcher(jdbcUrl).find()
                || ORACLE_THIN_USER_INFO.matcher(jdbcUrl).find()) {
            throw new IllegalArgumentException("JDBC URL 不得包含用户名或密码，请使用独立字段和 PasswordSafe");
        }
        driverClassName = requireText(driverClassName, "JDBC 驱动类不能为空");
        if (!JAVA_CLASS_NAME.matcher(driverClassName).matches()) {
            throw new IllegalArgumentException("JDBC 驱动类必须是完整 Java 类名");
        }
        if (dialect == MyBatisSqlDialect.GENERIC) {
            throw new IllegalArgumentException("Community JDBC 必须显式选择数据库方言");
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
            Path path = Path.of(requireText(value, "JDBC 驱动路径不能为空")).normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("JDBC 驱动路径必须是绝对路径");
            }
            if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".jar")) {
                throw new IllegalArgumentException("JDBC 驱动路径必须指向 JAR 文件");
            }
            return path.toString();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("JDBC 驱动路径无效", failure);
        }
    }

    private static @NotNull Optional<String> normalizeOptional(
            @NotNull Optional<String> value) {
        return value.map(String::trim).filter(text -> !text.isEmpty());
    }
}
