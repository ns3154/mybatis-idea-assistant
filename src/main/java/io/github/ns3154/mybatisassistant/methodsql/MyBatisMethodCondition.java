package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 一个字段比较条件。
 */
public record MyBatisMethodCondition(
        @NotNull MyBatisMethodField field,
        @NotNull MyBatisMethodComparison comparison) implements MyBatisMethodPredicate {
}
