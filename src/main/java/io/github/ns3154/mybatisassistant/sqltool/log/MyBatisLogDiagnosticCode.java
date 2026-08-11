package io.github.ns3154.mybatisassistant.sqltool.log;

/**
 * 日志 SQL 还原的稳定诊断编码，供界面、测试和后续批处理复用。
 */
public enum MyBatisLogDiagnosticCode {
    INPUT_TOO_LARGE,
    PARAMETERS_WITHOUT_PREPARING,
    PREPARING_WITHOUT_PARAMETERS,
    PLACEHOLDER_COUNT_MISMATCH,
    AMBIGUOUS_PARAMETERS,
    INVALID_PARAMETER,
    UNSUPPORTED_BINARY_PARAMETER,
    MALFORMED_SQL
}
