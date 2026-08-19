package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 保留 foreach 运行期迭代语义及其局部 item/index 作用域。
 */
public record MyBatisForeachNode(
        @NotNull MyBatisDynamicSqlExpression collection,
        @NotNull List<MyBatisDynamicSqlBinding> scopedBindings,
        @NotNull String open,
        @NotNull String close,
        @NotNull String separator,
        @NotNull Optional<Boolean> nullable,
        @NotNull MyBatisDynamicSqlNode body,
        @NotNull MyBatisSourceRange sourceRange) implements MyBatisDynamicSqlNode {
    public MyBatisForeachNode {
        scopedBindings = List.copyOf(scopedBindings);
    }
}
