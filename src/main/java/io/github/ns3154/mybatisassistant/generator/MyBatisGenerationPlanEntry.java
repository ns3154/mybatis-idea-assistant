package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 预览和写入共用的不可变文件计划。
 */
public record MyBatisGenerationPlanEntry(
        @NotNull MyBatisGeneratedArtifact artifact,
        @NotNull MyBatisGenerationPlanStatus status,
        @NotNull Optional<String> existingText,
        @NotNull Optional<String> proposedText,
        @NotNull Optional<MyBatisSafeMergeConflictCode> conflictCode,
        @NotNull Optional<String> message) {
    public MyBatisGenerationPlanEntry {
        if (status == MyBatisGenerationPlanStatus.CONFLICT) {
            if (conflictCode.isEmpty() || message.isEmpty() || proposedText.isPresent()) {
                throw new IllegalArgumentException("冲突计划缺少类型或原因");
            }
        } else if (proposedText.isEmpty() || conflictCode.isPresent()) {
            throw new IllegalArgumentException("可写计划必须包含候选文本且不能含冲突");
        }
    }
}
