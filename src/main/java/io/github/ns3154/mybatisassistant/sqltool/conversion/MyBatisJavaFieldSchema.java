package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 从 Java PSI 提取的稳定字段模型。
 */
public record MyBatisJavaFieldSchema(
        @NotNull String propertyName,
        @NotNull String columnName,
        @NotNull String javaType,
        boolean nullable,
        boolean primaryKey,
        boolean autoIncrement,
        boolean inherited,
        @NotNull Optional<String> comment) {
    public MyBatisJavaFieldSchema {
        if (propertyName.isBlank() || columnName.isBlank() || javaType.isBlank()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.java.field.identity.empty"));
        }
        comment = comment.filter(value -> !value.isBlank());
    }
}
