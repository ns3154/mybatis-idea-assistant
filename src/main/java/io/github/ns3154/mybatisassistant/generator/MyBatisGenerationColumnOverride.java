package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 用户对单列的显式命名、Java 类型与 TypeHandler 覆盖。
 */
public record MyBatisGenerationColumnOverride(
        @NotNull Optional<String> propertyName,
        @NotNull Optional<String> javaType,
        @NotNull Optional<String> typeHandler) {
    public MyBatisGenerationColumnOverride {
        propertyName = nonBlank(propertyName);
        javaType = nonBlank(javaType);
        typeHandler = nonBlank(typeHandler);
        propertyName.ifPresent(MyBatisGenerationNames::requireJavaIdentifier);
        javaType.ifPresent(MyBatisGenerationNames::requireJavaType);
        typeHandler.ifPresent(MyBatisGenerationNames::requireJavaType);
    }

    public static @NotNull MyBatisGenerationColumnOverride empty() {
        return new MyBatisGenerationColumnOverride(
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static @NotNull Optional<String> nonBlank(@NotNull Optional<String> value) {
        return value.map(String::trim).filter(candidate -> !candidate.isEmpty());
    }
}
