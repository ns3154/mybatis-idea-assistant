package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Join 查询中由用户显式选择的输出列。
 */
public record MyBatisJoinSelection(
        @NotNull String tableAlias,
        @NotNull MyBatisMethodField field,
        @NotNull Optional<String> outputAlias) {
    public MyBatisJoinSelection {
        if (tableAlias.isBlank()) {
            throw new IllegalArgumentException("Join 输出表别名不能为空");
        }
        outputAlias = outputAlias.map(String::trim).filter(value -> !value.isEmpty());
    }
}
