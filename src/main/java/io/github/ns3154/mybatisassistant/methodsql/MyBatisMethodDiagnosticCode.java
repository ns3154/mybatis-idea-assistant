package io.github.ns3154.mybatisassistant.methodsql;

/**
 * 方法名语法的稳定失败分类。
 */
public enum MyBatisMethodDiagnosticCode {
    EMPTY_NAME,
    METHOD_NAME_TOO_LONG,
    UNKNOWN_OPERATION,
    UNKNOWN_FIELD,
    AMBIGUOUS_SYNTAX,
    INVALID_LIMIT,
    INVALID_SUBJECT,
    INVALID_ORDER,
    PREDICATE_REQUIRED,
    UNSUPPORTED_COMBINATION
}
