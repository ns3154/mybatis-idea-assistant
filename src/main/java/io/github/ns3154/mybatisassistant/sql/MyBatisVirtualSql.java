package io.github.ns3154.mybatisassistant.sql;

import io.github.ns3154.mybatisassistant.dynamic.MyBatisMappedText;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 供 SQL PSI 消费的有界代表 SQL 及其原文映射。
 */
public record MyBatisVirtualSql(
        @NotNull MyBatisMappedText mappedText,
        @NotNull List<MyBatisVirtualSqlDiagnostic> diagnostics,
        boolean representativeOnly) {
    public MyBatisVirtualSql {
        diagnostics = List.copyOf(diagnostics);
    }
}
