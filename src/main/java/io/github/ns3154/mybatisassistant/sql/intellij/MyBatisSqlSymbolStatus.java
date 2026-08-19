package io.github.ns3154.mybatisassistant.sql.intellij;

/**
 * SQL 符号相对当前完整元数据的解析状态。
 */
public enum MyBatisSqlSymbolStatus {
    RESOLVED,
    MISSING,
    AMBIGUOUS,
    UNKNOWN
}
