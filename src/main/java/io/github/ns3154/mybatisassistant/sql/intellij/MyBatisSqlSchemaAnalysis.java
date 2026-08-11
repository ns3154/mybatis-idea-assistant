package io.github.ns3154.mybatisassistant.sql.intellij;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一条代表 SQL 的表列分析结果。
 */
public record MyBatisSqlSchemaAnalysis(
        @NotNull List<MyBatisSqlSymbolOccurrence> occurrences,
        boolean metadataComplete) {
    public MyBatisSqlSchemaAnalysis {
        occurrences = List.copyOf(occurrences);
    }
}
