package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 可回映射到 XML 的保守编译诊断。
 */
public record MyBatisDynamicSqlDiagnostic(
        @NotNull MyBatisDynamicSqlDiagnosticCode code,
        @NotNull String message,
        @NotNull MyBatisSourceRange sourceRange) {
}
