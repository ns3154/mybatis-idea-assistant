package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 复用统一 SQL 词法扫描器，仅替换正常 SQL 上下文中的问号。
 */
public final class MyBatisJdbcPlaceholderRewriter {
    private MyBatisJdbcPlaceholderRewriter() {
    }

    public static @NotNull MyBatisJdbcPlaceholderRewriteResult rewrite(
            @NotNull String sql,
            @NotNull List<String> replacements) {
        try {
            MyBatisSqlLexicalScanner.ScanResult scanned =
                    MyBatisSqlLexicalScanner.scan(sql, replacements);
            if (scanned.placeholderCount() != replacements.size()) {
                return new MyBatisJdbcPlaceholderRewriteResult.Failure(
                        "JDBC 占位符数量为 " + scanned.placeholderCount()
                                + "，替换参数数量为 " + replacements.size());
            }
            return new MyBatisJdbcPlaceholderRewriteResult.Success(
                    scanned.renderedSql(), scanned.placeholderCount());
        } catch (MyBatisSqlLexicalScanner.MalformedSqlException malformed) {
            return new MyBatisJdbcPlaceholderRewriteResult.Failure(malformed.getMessage());
        }
    }
}
