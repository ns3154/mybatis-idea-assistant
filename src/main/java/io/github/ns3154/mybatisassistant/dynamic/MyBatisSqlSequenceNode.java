package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 按 XML 原始顺序保存的动态 SQL 节点序列。
 */
public record MyBatisSqlSequenceNode(@NotNull List<MyBatisDynamicSqlNode> children)
        implements MyBatisDynamicSqlNode {
    public MyBatisSqlSequenceNode {
        children = List.copyOf(children);
    }
}
