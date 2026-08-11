package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 复用日志还原器的 SQL 词法规则统计真实 JDBC 占位符。
 */
public final class MyBatisSqlPlaceholderAnalyzer {
    private MyBatisSqlPlaceholderAnalyzer() {
    }

    public static @NotNull MyBatisSqlPlaceholderAnalysis analyze(@NotNull String sql) {
        try {
            MyBatisSqlLexicalScanner.ScanResult result =
                    MyBatisSqlLexicalScanner.scan(sql, List.of());
            return new MyBatisSqlPlaceholderAnalysis(
                    result.placeholderCount(), true, "");
        } catch (MyBatisSqlLexicalScanner.MalformedSqlException malformed) {
            return new MyBatisSqlPlaceholderAnalysis(
                    0, false, "SQL 包含未闭合的引号、标识符或注释");
        }
    }
}
