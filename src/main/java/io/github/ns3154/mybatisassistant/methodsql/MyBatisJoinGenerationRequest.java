package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Join 生成必须包含基表、全部关系与输出列的显式选择。
 */
public record MyBatisJoinGenerationRequest(
        @NotNull MyBatisMethodSchema baseSchema,
        @NotNull String baseAlias,
        @NotNull List<MyBatisJoinSpec> joins,
        @NotNull List<MyBatisJoinSelection> selections,
        @NotNull MyBatisSqlDialect dialect,
        boolean escapeIdentifiers) {
    public MyBatisJoinGenerationRequest {
        joins = List.copyOf(joins);
        selections = List.copyOf(selections);
        if (baseAlias.isBlank() || joins.isEmpty() || selections.isEmpty()) {
            throw new IllegalArgumentException("Join 基表别名、关系和输出列不能为空");
        }
    }
}
