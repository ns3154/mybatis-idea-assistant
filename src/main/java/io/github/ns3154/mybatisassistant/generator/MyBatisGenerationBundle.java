package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次表生成的全部候选产物。
 */
public record MyBatisGenerationBundle(
        @NotNull String entityName,
        @NotNull List<MyBatisGeneratedArtifact> artifacts) {
    public MyBatisGenerationBundle {
        MyBatisGenerationNames.requireJavaIdentifier(entityName);
        artifacts = List.copyOf(artifacts);
    }
}
