package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Wrapper 只按用户明确指定的框架及版本生成，不做类路径猜测。
 */
public record MyBatisWrapperGenerationRequest(
        @NotNull MyBatisMethodSchema schema,
        @NotNull MyBatisMethodQuery query,
        @NotNull MyBatisMethodGeneration methodGeneration,
        @NotNull MyBatisWrapperFramework framework,
        @NotNull String frameworkVersion,
        @NotNull String entityType,
        @NotNull Set<Integer> optionalConditionIndexes) {
    public MyBatisWrapperGenerationRequest {
        if (frameworkVersion.isBlank() || entityType.isBlank()) {
            throw new IllegalArgumentException("Wrapper 框架版本与实体类型不能为空");
        }
        MyBatisJavaTypeValidator.requireQualifiedName(entityType, "Wrapper 实体类型");
        optionalConditionIndexes = Set.copyOf(optionalConditionIndexes);
    }
}
