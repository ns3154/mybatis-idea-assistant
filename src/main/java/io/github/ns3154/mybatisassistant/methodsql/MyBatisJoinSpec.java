package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 用户明确选择的一张目标表及其一个或多个关联条件。
 */
public record MyBatisJoinSpec(
        @NotNull MyBatisJoinType type,
        @NotNull MyBatisMethodSchema targetSchema,
        @NotNull String targetAlias,
        @NotNull List<MyBatisJoinRelation> relations) {
    public MyBatisJoinSpec {
        relations = List.copyOf(relations);
        if (targetAlias.isBlank() || relations.isEmpty()) {
            throw new IllegalArgumentException("Join 目标别名和关联条件不能为空");
        }
    }
}
