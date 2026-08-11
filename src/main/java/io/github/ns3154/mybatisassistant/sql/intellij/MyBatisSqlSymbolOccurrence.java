package io.github.ns3154.mybatisassistant.sql.intellij;

import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * SQL PSI 中一个表或列引用的保守解析结果。
 */
public record MyBatisSqlSymbolOccurrence(
        @NotNull MyBatisSqlSymbolKind kind,
        @NotNull MyBatisSqlSymbolStatus status,
        @NotNull String name,
        @NotNull Optional<String> qualifier,
        @NotNull MyBatisTextRange virtualRange,
        @NotNull List<String> candidates) {
    public MyBatisSqlSymbolOccurrence {
        candidates = List.copyOf(candidates);
    }
}
