package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 完整 token 流与可恢复词法诊断。
 */
public record MyBatisOgnlLexResult(
        @NotNull List<MyBatisOgnlToken> tokens,
        @NotNull List<MyBatisOgnlDiagnostic> diagnostics) {
    public MyBatisOgnlLexResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }
}
