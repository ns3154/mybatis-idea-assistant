package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 可映射回方法名文本的位置化语法错误。
 */
public record MyBatisMethodDiagnostic(
        @NotNull MyBatisMethodDiagnosticCode code,
        int offset,
        int length,
        @NotNull String message) {
    public MyBatisMethodDiagnostic {
        if (offset < 0 || length < 0 || message.isBlank()) {
            throw new IllegalArgumentException("方法名诊断范围或文案不合法");
        }
    }
}
