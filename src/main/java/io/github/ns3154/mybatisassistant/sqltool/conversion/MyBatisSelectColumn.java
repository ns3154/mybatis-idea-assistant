package io.github.ns3154.mybatisassistant.sqltool.conversion;

import org.jetbrains.annotations.NotNull;

/**
 * SELECT 显式投影与其保守 Java 属性名。
 */
public record MyBatisSelectColumn(
        @NotNull String sqlLabel,
        @NotNull String javaProperty) {
}
