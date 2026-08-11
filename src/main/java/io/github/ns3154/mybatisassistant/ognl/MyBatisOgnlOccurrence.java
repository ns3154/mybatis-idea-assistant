package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 表达式中一个可引用名称及其精确半开范围。
 */
public record MyBatisOgnlOccurrence(
        @NotNull String name,
        @NotNull MyBatisOgnlRange range,
        @NotNull MyBatisOgnlSymbolKind kind,
        @NotNull MyBatisOgnlSemanticResult result) {
    public MyBatisOgnlOccurrence {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
    }
}
