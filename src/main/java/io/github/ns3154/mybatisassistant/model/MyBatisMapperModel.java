package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public record MyBatisMapperModel(
        @NotNull String qualifiedName,
        @NotNull List<MyBatisMapperEvidence> evidence,
        @NotNull List<MyBatisMapperMethodModel> methods) {
    public MyBatisMapperModel {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        evidence = List.copyOf(evidence);
        methods = List.copyOf(methods);
        if (qualifiedName.isBlank() || evidence.isEmpty()) {
            throw new IllegalArgumentException("Mapper 模型必须包含全限定名和识别证据");
        }
    }
}
