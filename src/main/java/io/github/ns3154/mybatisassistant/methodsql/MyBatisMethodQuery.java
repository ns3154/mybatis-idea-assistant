package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 方法名的唯一解析结果；subjectFields 的含义由 operation 决定。
 */
public record MyBatisMethodQuery(
        @NotNull String methodName,
        @NotNull MyBatisMethodOperation operation,
        @NotNull List<MyBatisMethodField> subjectFields,
        boolean distinct,
        @NotNull OptionalInt limit,
        boolean paged,
        boolean singleResult,
        @NotNull Optional<MyBatisMethodPredicate> predicate,
        @NotNull List<MyBatisMethodOrder> orders) {
    public MyBatisMethodQuery {
        subjectFields = List.copyOf(subjectFields);
        orders = List.copyOf(orders);
        if (limit.isPresent() && limit.getAsInt() <= 0) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.query.limit"));
        }
    }
}
