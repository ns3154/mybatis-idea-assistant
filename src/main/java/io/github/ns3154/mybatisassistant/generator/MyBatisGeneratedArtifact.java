package io.github.ns3154.mybatisassistant.generator;

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
            throw new IllegalArgumentException("生成产物必须包含内容和稳定区域");
        }
        regionIds = Set.copyOf(regionIds);
    }
}
