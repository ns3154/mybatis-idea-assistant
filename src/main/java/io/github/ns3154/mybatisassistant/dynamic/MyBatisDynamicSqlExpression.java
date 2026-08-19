package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * S5 只保存表达式文本与来源，完整 OGNL AST 由 S6 构建。
 */
public record MyBatisDynamicSqlExpression(
        @NotNull String text,
        @NotNull MyBatisSourceRange sourceRange) {
}
