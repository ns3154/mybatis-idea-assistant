package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 同一优先级的一组条件节点。
 */
public record MyBatisMethodJunction(
        @NotNull MyBatisMethodJunctionKind kind,
        @NotNull List<MyBatisMethodPredicate> children) implements MyBatisMethodPredicate {
    public MyBatisMethodJunction {
        children = List.copyOf(children);
        if (children.size() < 2) {
            throw new IllegalArgumentException("条件连接至少需要两个子节点");
        }
    }
}
