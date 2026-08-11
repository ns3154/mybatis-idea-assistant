package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * 批量生成在任何写入前完成的全量计划。
 */
public record MyBatisGenerationPlan(@NotNull List<MyBatisGenerationPlanEntry> entries) {
    public MyBatisGenerationPlan {
        entries = List.copyOf(entries);
    }

    public boolean hasConflicts() {
        return entries.stream().anyMatch(entry -> entry.status()
                == MyBatisGenerationPlanStatus.CONFLICT);
    }

    public boolean hasChanges() {
        return entries.stream().anyMatch(entry -> entry.status()
                == MyBatisGenerationPlanStatus.CREATE
                || entry.status() == MyBatisGenerationPlanStatus.UPDATE);
    }

    public @NotNull MyBatisGenerationPlan select(@NotNull Set<String> relativePaths) {
        return new MyBatisGenerationPlan(entries.stream()
                .filter(entry -> relativePaths.contains(entry.artifact().relativePath()))
                .toList());
    }
}
