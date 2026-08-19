package io.github.ns3154.mybatisassistant.sql;

import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceRange;
import org.jetbrains.annotations.NotNull;

/**
 * 不依赖异常文本的虚拟 SQL 诊断。
 */
public record MyBatisVirtualSqlDiagnostic(
        @NotNull MyBatisVirtualSqlDiagnosticCode code,
        @NotNull String message,
        @NotNull MyBatisSourceRange sourceRange) {
}
