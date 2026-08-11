package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 可直接插入方法体的 Wrapper 初始化与条件代码。
 */
public record MyBatisWrapperGeneration(
        @NotNull String wrapperType,
        @NotNull String variableName,
        @NotNull String code) {
}
