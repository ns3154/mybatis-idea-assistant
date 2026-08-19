package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 完全在内存中渲染的单个候选文件。
 */
public record MyBatisGeneratedArtifact(
        @NotNull MyBatisGenerationArtifactKind kind,
        @NotNull String relativePath,
        @NotNull String content,
        @NotNull Set<String> regionIds) {
    public MyBatisGeneratedArtifact {
        relativePath = MyBatisGenerationNames.requireRelativePath(relativePath);
        if (content.isBlank() || regionIds.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.artifact.incomplete"));
        }
        regionIds = Set.copyOf(regionIds);
    }
}
