package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import org.jetbrains.annotations.NotNull;

import java.sql.Types;
import java.util.Optional;

/**
 * 保守的 JDBC 到 Java 类型映射；不识别的类型显式回退为 Object。
 */
public record MyBatisJavaTypeMapping(
        @NotNull String canonicalType,
        @NotNull String simpleType,
        @NotNull Optional<String> importName) {
    public static @NotNull MyBatisJavaTypeMapping resolve(
            @NotNull MyBatisDatabaseColumn column,
            @NotNull MyBatisGenerationColumnOverride override) {
        String canonical = override.javaType().orElseGet(() -> defaultType(column.jdbcType()));
        String component = canonical.endsWith("[]")
                ? canonical.substring(0, canonical.length() - 2)
                : canonical;
        int separator = component.lastIndexOf('.');
        String simple = separator < 0 ? canonical : component.substring(separator + 1)
                + (canonical.endsWith("[]") ? "[]" : "");
        Optional<String> importName = separator < 0 || component.startsWith("java.lang.")
                ? Optional.empty()
                : Optional.of(component);
        return new MyBatisJavaTypeMapping(canonical, simple, importName);
    }

    private static @NotNull String defaultType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> "java.lang.Byte";
            case Types.SMALLINT -> "java.lang.Short";
            case Types.INTEGER -> "java.lang.Integer";
            case Types.BIGINT -> "java.lang.Long";
            case Types.FLOAT, Types.REAL -> "java.lang.Float";
            case Types.DOUBLE -> "java.lang.Double";
            case Types.NUMERIC, Types.DECIMAL -> "java.math.BigDecimal";
            case Types.BIT, Types.BOOLEAN -> "java.lang.Boolean";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR,
                    Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
                    Types.SQLXML -> "java.lang.String";
            case Types.DATE -> "java.time.LocalDate";
            case Types.TIME -> "java.time.LocalTime";
            case Types.TIME_WITH_TIMEZONE -> "java.time.OffsetTime";
            case Types.TIMESTAMP -> "java.time.LocalDateTime";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "java.time.OffsetDateTime";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "byte[]";
            default -> "java.lang.Object";
        };
    }
}
