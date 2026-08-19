package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次本地还原的不可变报告。
 */
public record MyBatisLogRestoreReport(
        @NotNull List<MyBatisRestoredStatement> statements,
        @NotNull List<MyBatisLogDiagnostic> diagnostics) {
    public MyBatisLogRestoreReport {
        statements = List.copyOf(statements);
        diagnostics = List.copyOf(diagnostics);
    }
}
