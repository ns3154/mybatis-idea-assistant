package io.github.ns3154.mybatisassistant.sqltool.testgen;

import org.jetbrains.annotations.NotNull;

/**
 * 可复制但不自动写入的 JUnit 骨架。
 */
public record MyBatisMapperTestGeneration(
        @NotNull String suggestedFileName,
        @NotNull String source) {
}
