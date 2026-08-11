package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 保留字符级来源的 SQL 文本节点。
 */
public record MyBatisSqlTextNode(@NotNull MyBatisMappedText content)
        implements MyBatisDynamicSqlNode {
}
