package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 一个目标表字段与此前已注册表字段之间的显式 ON 等值关系。
 */
public record MyBatisJoinRelation(
        @NotNull String sourceAlias,
        @NotNull MyBatisMethodField sourceField,
        @NotNull MyBatisMethodField targetField) {
    public MyBatisJoinRelation {
        if (sourceAlias.isBlank()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.join.source.alias.empty"));
        }
    }
}
