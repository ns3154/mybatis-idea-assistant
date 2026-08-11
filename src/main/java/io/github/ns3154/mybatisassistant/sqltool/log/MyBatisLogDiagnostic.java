package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

/**
 * 一条不携带原始日志内容的安全诊断。
 */
public record MyBatisLogDiagnostic(
        @NotNull MyBatisLogDiagnosticCode code,
        int lineNumber,
        @NotNull String message) {
    public MyBatisLogDiagnostic {
        if (lineNumber < 0) {
            throw new IllegalArgumentException("行号不能为负数");
        }
    }
}
