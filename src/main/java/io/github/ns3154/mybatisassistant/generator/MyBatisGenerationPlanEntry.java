package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
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
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.error.plan.conflict.incomplete"));
            }
        } else if (proposedText.isEmpty() || conflictCode.isPresent()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.plan.writable.invalid"));
        }
    }
}
