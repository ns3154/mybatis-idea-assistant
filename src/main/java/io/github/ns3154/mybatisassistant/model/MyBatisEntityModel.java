package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record MyBatisEntityModel(
        @NotNull String canonicalType,
        @NotNull MyBatisEntityKind kind,
        @Nullable String qualifiedName,
        @NotNull List<MyBatisEntityModel> typeArguments,
        boolean nullable) {
    public MyBatisEntityModel {
        Objects.requireNonNull(canonicalType, "canonicalType");
        Objects.requireNonNull(kind, "kind");
        typeArguments = List.copyOf(typeArguments);
        if (canonicalType.isBlank()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.entity.type.empty"));
        }
    }
}
