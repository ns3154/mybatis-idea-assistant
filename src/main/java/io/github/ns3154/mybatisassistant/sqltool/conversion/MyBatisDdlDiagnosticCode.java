package io.github.ns3154.mybatisassistant.sqltool.conversion;

/**
 * DDL 转换阶段的稳定诊断类型。
 */
public enum MyBatisDdlDiagnosticCode {
    INPUT_TOO_LARGE,
    NOT_CREATE_TABLE,
    MULTIPLE_STATEMENTS,
    MALFORMED_DDL,
    NO_COLUMNS,
    DUPLICATE_COLUMN,
    UNSUPPORTED_DEFINITION
}
