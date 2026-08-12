package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.model.MyBatisFrameworkMapperBinding;
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

    /**
     * 从统一框架 Mapper 模型创建请求，实体类型不再由调用方重复填写。
     */
    public static @NotNull MyBatisWrapperGenerationRequest fromFrameworkBinding(
            @NotNull MyBatisMethodSchema schema,
            @NotNull MyBatisMethodQuery query,
            @NotNull MyBatisMethodGeneration methodGeneration,
            @NotNull MyBatisFrameworkMapperBinding binding,
            @NotNull String frameworkVersion,
            @NotNull Set<Integer> optionalConditionIndexes) {
        MyBatisWrapperFramework framework = MyBatisWrapperFramework
                .fromFrameworkKind(binding.framework())
                .orElseThrow(() -> new IllegalArgumentException(
                        binding.framework().displayName() + " 暂不支持 Wrapper 生成"));
        return new MyBatisWrapperGenerationRequest(
                schema,
                query,
                methodGeneration,
                framework,
                frameworkVersion,
                binding.entity().qualifiedName(),
                optionalConditionIndexes);
    }
}
