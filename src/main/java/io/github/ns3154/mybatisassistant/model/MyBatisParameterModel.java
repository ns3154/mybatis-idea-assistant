package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record MyBatisParameterModel(
        @NotNull String name,
        @NotNull String canonicalType,
        boolean explicitlyNamed,
        @NotNull MyBatisEntityModel entity) {
    public MyBatisParameterModel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(canonicalType, "canonicalType");
        Objects.requireNonNull(entity, "entity");
        if (name.isBlank() || canonicalType.isBlank()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.parameter.model.empty"));
        }
    }
}
