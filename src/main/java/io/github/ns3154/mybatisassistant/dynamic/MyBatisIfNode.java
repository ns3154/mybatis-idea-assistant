package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 不求值、不展开组合的 if 条件节点。
 */
public record MyBatisIfNode(
        @NotNull MyBatisDynamicSqlExpression condition,
        @NotNull MyBatisDynamicSqlNode body,
        @NotNull MyBatisSourceRange sourceRange) implements MyBatisDynamicSqlNode {
}
