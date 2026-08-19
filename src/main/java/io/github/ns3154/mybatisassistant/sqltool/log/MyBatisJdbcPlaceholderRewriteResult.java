package io.github.ns3154.mybatisassistant.sqltool.log;

import org.jetbrains.annotations.NotNull;

/**
 * JDBC 问号占位符安全重写结果。
 */
public sealed interface MyBatisJdbcPlaceholderRewriteResult {
    record Success(@NotNull String sql, int placeholderCount)
            implements MyBatisJdbcPlaceholderRewriteResult {
    }

    record Failure(@NotNull String message)
            implements MyBatisJdbcPlaceholderRewriteResult {
    }
}
