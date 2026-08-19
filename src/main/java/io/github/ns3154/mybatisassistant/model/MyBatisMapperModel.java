package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public record MyBatisMapperModel(
        @NotNull String qualifiedName,
        @NotNull List<MyBatisMapperEvidence> evidence,
        @NotNull List<MyBatisMapperMethodModel> methods,
        @NotNull List<MyBatisFrameworkMapperBinding> frameworkBindings) {
    public MyBatisMapperModel {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        evidence = List.copyOf(evidence);
        methods = List.copyOf(methods);
        frameworkBindings = List.copyOf(frameworkBindings);
        if (qualifiedName.isBlank() || evidence.isEmpty()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.mapper.model.incomplete"));
        }
    }

    public MyBatisMapperModel(
            @NotNull String qualifiedName,
            @NotNull List<MyBatisMapperEvidence> evidence,
            @NotNull List<MyBatisMapperMethodModel> methods) {
        this(qualifiedName, evidence, methods, List.of());
    }
}
