package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
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
        @NotNull MyBatisSqlDialect dialect,
        boolean escapeIdentifiers,
        @NotNull Set<Integer> optionalConditionIndexes) {
    public MyBatisWrapperGenerationRequest {
        if (frameworkVersion.isBlank() || entityType.isBlank()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.wrapper.identity.empty"));
        }
        MyBatisJavaTypeValidator.requireQualifiedName(entityType,
                MyBatisMethodSqlMessages.message("methodsql.role.wrapper.entity"));
        optionalConditionIndexes = Set.copyOf(optionalConditionIndexes);
    }

    /**
     * 保留旧调用方的源码兼容性；新入口应显式传入实际数据库方言和引用策略。
     */
    public MyBatisWrapperGenerationRequest(
            @NotNull MyBatisMethodSchema schema,
            @NotNull MyBatisMethodQuery query,
            @NotNull MyBatisMethodGeneration methodGeneration,
            @NotNull MyBatisWrapperFramework framework,
            @NotNull String frameworkVersion,
            @NotNull String entityType,
            @NotNull Set<Integer> optionalConditionIndexes) {
        this(schema, query, methodGeneration, framework, frameworkVersion, entityType,
                MyBatisSqlDialect.GENERIC, false, optionalConditionIndexes);
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
                        MyBatisMethodSqlMessages.message(
                                "methodsql.error.wrapper.framework.unsupported",
                                binding.framework().displayName())));
        return new MyBatisWrapperGenerationRequest(
                schema,
                query,
                methodGeneration,
                framework,
                frameworkVersion,
                binding.entity().qualifiedName(),
                MyBatisSqlDialect.GENERIC,
                false,
                optionalConditionIndexes);
    }
}
