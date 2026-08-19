package io.github.ns3154.mybatisassistant.sqltool.log;

/**
 * JDBC 问号占位符扫描结果。
 */
public record MyBatisSqlPlaceholderAnalysis(
        int placeholderCount,
        boolean structurallyValid,
        String message) {
}
