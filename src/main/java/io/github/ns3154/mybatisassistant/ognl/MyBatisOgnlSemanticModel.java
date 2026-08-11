package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * 单个 XML OGNL 属性的解析树、根结果与可引用名称快照。
 */
public record MyBatisOgnlSemanticModel(
        @NotNull MyBatisOgnlParseResult parseResult,
        @NotNull MyBatisOgnlSemanticResult rootResult,
        @NotNull List<MyBatisOgnlOccurrence> occurrences) {
    public MyBatisOgnlSemanticModel {
        Objects.requireNonNull(parseResult, "parseResult");
        Objects.requireNonNull(rootResult, "rootResult");
        occurrences = List.copyOf(occurrences);
    }
}
