package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 始终返回根 AST 的可恢复解析结果。
 */
public record MyBatisOgnlParseResult(
        @NotNull MyBatisOgnlExpression root,
        @NotNull List<MyBatisOgnlToken> tokens,
        @NotNull List<MyBatisOgnlDiagnostic> diagnostics) {
    public MyBatisOgnlParseResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }
}
