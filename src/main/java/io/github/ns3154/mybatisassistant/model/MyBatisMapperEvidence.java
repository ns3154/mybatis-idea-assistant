package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record MyBatisMapperEvidence(
        @NotNull MyBatisMapperEvidenceKind kind,
        @NotNull String detail) {
    public MyBatisMapperEvidence {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.mapper.evidence.empty"));
        }
    }
}
