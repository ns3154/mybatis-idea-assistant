package io.github.ns3154.mybatisassistant.ognl;

/**
 * 词法与语法层稳定诊断代码。
 */
public enum MyBatisOgnlDiagnosticCode {
    UNEXPECTED_CHARACTER,
    UNTERMINATED_STRING,
    INVALID_ESCAPE,
    INVALID_NUMBER,
    EXPECTED_EXPRESSION,
    EXPECTED_TOKEN,
    EXPECTED_IDENTIFIER,
    TRAILING_TOKEN,
    MAXIMUM_NESTING_EXCEEDED
}
