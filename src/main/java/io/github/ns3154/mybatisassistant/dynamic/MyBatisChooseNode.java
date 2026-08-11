package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 保留 when 短路顺序的 choose 节点，不生成分支笛卡尔积。
 */
public record MyBatisChooseNode(
        @NotNull List<MyBatisWhenBranch> branches,
        @NotNull Optional<MyBatisDynamicSqlNode> otherwiseBranch,
        @NotNull MyBatisSourceRange sourceRange) implements MyBatisDynamicSqlNode {
    public MyBatisChooseNode {
        branches = List.copyOf(branches);
    }
}
