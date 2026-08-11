package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * choose 中保持原始顺序的 when 分支。
 */
public record MyBatisWhenBranch(
        @NotNull MyBatisDynamicSqlExpression condition,
        @NotNull MyBatisDynamicSqlNode body,
        @NotNull MyBatisSourceRange sourceRange) {
}
