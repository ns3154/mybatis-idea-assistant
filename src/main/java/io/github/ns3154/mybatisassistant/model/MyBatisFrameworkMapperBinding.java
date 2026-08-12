package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Mapper 与一个已支持框架基类之间的类型化绑定。
 */
public record MyBatisFrameworkMapperBinding(
        @NotNull MyBatisFrameworkKind framework,
        @NotNull MyBatisEntityModel entity,
        @NotNull List<String> frameworkMethodSignatures,
        @NotNull List<String> frameworkDeclaringTypes) {
    public MyBatisFrameworkMapperBinding {
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(entity, "entity");
        frameworkMethodSignatures = List.copyOf(frameworkMethodSignatures);
        frameworkDeclaringTypes = List.copyOf(frameworkDeclaringTypes);
        if (entity.qualifiedName() == null
                || entity.kind() != MyBatisEntityKind.CLASS) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.framework.entity.concrete"));
        }
        if (!frameworkDeclaringTypes.contains(framework.baseMapperQualifiedName())) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.framework.declaring.type"));
        }
    }

    public MyBatisFrameworkMapperBinding(
            @NotNull MyBatisFrameworkKind framework,
            @NotNull MyBatisEntityModel entity,
            @NotNull List<String> frameworkMethodSignatures) {
        this(
                framework,
                entity,
                frameworkMethodSignatures,
                List.of(framework.baseMapperQualifiedName()));
    }
}
