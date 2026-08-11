package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 一个有明确声明位置的动态 SQL 词法绑定。
 */
public record MyBatisDynamicSqlBinding(
        @NotNull String name,
        @NotNull MyBatisDynamicSqlBindingKind kind,
        @NotNull Optional<MyBatisDynamicSqlExpression> initializer,
        @NotNull MyBatisSourceRange sourceRange) {
}
