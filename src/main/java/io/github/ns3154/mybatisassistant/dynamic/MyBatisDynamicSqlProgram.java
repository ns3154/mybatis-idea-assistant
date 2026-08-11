package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 一个 statement 的符号化动态 SQL 编译结果。
 */
public record MyBatisDynamicSqlProgram(
        @NotNull MyBatisDynamicSqlNode root,
        @NotNull List<MyBatisDynamicSqlDiagnostic> diagnostics,
        @NotNull Optional<MyBatisMappedText> staticSql) {
    public MyBatisDynamicSqlProgram {
        diagnostics = List.copyOf(diagnostics);
    }
}
