package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

/**
 * 不依赖异常文本的 OGNL 词法或语法诊断。
 */
public record MyBatisOgnlDiagnostic(
        @NotNull MyBatisOgnlDiagnosticCode code,
        @NotNull String message,
        @NotNull MyBatisOgnlRange range) {
}
