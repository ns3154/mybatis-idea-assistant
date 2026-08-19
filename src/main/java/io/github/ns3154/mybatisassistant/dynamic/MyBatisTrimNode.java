package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 保留前后缀和覆盖规则的 trim/where/set 节点。
 */
public record MyBatisTrimNode(
        @NotNull MyBatisTrimKind kind,
        @NotNull String prefix,
        @NotNull String suffix,
        @NotNull String prefixOverrides,
        @NotNull String suffixOverrides,
        @NotNull MyBatisDynamicSqlNode body,
        @NotNull MyBatisSourceRange sourceRange) implements MyBatisDynamicSqlNode {
}
