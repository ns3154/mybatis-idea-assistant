package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 一次双向查询返回的有效映射范围。
 */
public record MyBatisSourceMapping(
        @NotNull MyBatisTextRange virtualRange,
        @NotNull MyBatisSourceRange sourceRange,
        @NotNull MyBatisSourceMapKind kind) {
}
