package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 方法名词法可引用的确定字段。
 */
public record MyBatisMethodField(
        @NotNull String propertyName,
        @NotNull String methodToken,
        @NotNull String columnName,
        @NotNull String javaType,
        @NotNull Optional<String> typeHandler,
        int jdbcType,
        boolean nullable,
        boolean primaryKey,
        boolean foreignKey) {
    public MyBatisMethodField {
        if (propertyName.isBlank() || methodToken.isBlank() || columnName.isBlank()
                || javaType.isBlank()) {
            throw new IllegalArgumentException("方法字段名称与类型不能为空");
        }
        if (!Character.isUpperCase(methodToken.codePointAt(0))) {
            throw new IllegalArgumentException("方法字段词元必须以大写字母开头：" + methodToken);
        }
    }
}
