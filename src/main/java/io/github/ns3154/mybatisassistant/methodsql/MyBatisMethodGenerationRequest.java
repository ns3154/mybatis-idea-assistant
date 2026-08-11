package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 方法签名与 XML 生成只消费已成功解析的 AST 和显式选项。
 */
public record MyBatisMethodGenerationRequest(
        @NotNull MyBatisMethodSchema schema,
        @NotNull MyBatisMethodQuery query,
        @NotNull MyBatisSqlDialect dialect,
        @NotNull String entityType,
        boolean escapeIdentifiers,
        @NotNull Set<Integer> optionalConditionIndexes) {
    public MyBatisMethodGenerationRequest {
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("实体类型不能为空");
        }
        MyBatisJavaTypeValidator.requireQualifiedName(entityType, "实体类型");
        optionalConditionIndexes = Set.copyOf(optionalConditionIndexes);
        if (optionalConditionIndexes.stream().anyMatch(index -> index < 0)) {
            throw new IllegalArgumentException("动态条件序号不能为负数");
        }
    }
}
