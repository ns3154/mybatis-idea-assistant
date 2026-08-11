package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * bind 声明节点；它在所在序列中仅对后续节点可见。
 */
public record MyBatisBindNode(
        @NotNull MyBatisDynamicSqlBinding binding,
        @NotNull MyBatisSourceRange sourceRange) implements MyBatisDynamicSqlNode {
}
