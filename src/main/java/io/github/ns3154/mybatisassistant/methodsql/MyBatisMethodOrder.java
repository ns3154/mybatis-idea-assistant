package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 确定字段上的排序。
 */
public record MyBatisMethodOrder(
        @NotNull MyBatisMethodField field,
        @NotNull Direction direction) {
    public enum Direction {
        ASCENDING,
        DESCENDING
    }
}
