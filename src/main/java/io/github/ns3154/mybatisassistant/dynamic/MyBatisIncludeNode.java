package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 已精确解析并展开的 include，保留调用点、fragment 与 property 绑定来源。
 */
public record MyBatisIncludeNode(
        @NotNull String namespace,
        @NotNull String fragmentId,
        @NotNull List<MyBatisDynamicSqlBinding> properties,
        @NotNull MyBatisDynamicSqlNode expandedBody,
        @NotNull MyBatisSourceRange includeSourceRange,
        @NotNull MyBatisSourceRange fragmentSourceRange) implements MyBatisDynamicSqlNode {
    public MyBatisIncludeNode {
        properties = List.copyOf(properties);
    }
}
