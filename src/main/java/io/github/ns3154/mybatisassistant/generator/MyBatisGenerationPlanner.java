package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProgressIndicator;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.ns3154.mybatisassistant.util.MyBatisReadActionSupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在写入前逐目标短读并计算新建、更新、无变化与冲突。
 */
public final class MyBatisGenerationPlanner {
    private MyBatisGenerationPlanner() {
    }

    public static @NotNull MyBatisGenerationPlan plan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull List<MyBatisGenerationBundle> bundles) {
        return planInternal(project, projectRoot, bundles, null, 0.0);
    }

    /**
     * 在已有进度区间的后半段构建预览计划，供批量生成持续报告进度。
     */
    public static @NotNull MyBatisGenerationPlan plan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull List<MyBatisGenerationBundle> bundles,
            @NotNull ProgressIndicator indicator,
            double startFraction) {
        if (!Double.isFinite(startFraction)
                || startFraction < 0.0
                || startFraction > 1.0) {
            throw new IllegalArgumentException("startFraction must be between 0 and 1");
        }
        return planInternal(project, projectRoot, bundles, indicator, startFraction);
    }

    private static @NotNull MyBatisGenerationPlan planInternal(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull List<MyBatisGenerationBundle> bundles,
            @Nullable ProgressIndicator indicator,
            double startFraction) {
        if (!projectRoot.isDirectory()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.base.directory.invalid"));
        }
        updateProgress(indicator, startFraction);
        Map<String, MyBatisGeneratedArtifact> unique = new LinkedHashMap<>();
        Map<String, String> collisions = new LinkedHashMap<>();
        for (MyBatisGenerationBundle bundle : bundles) {
            for (MyBatisGeneratedArtifact artifact : bundle.artifacts()) {
                checkCanceled(indicator);
                MyBatisGeneratedArtifact previous = unique.putIfAbsent(
                        artifact.relativePath(), artifact);
                if (previous != null && !previous.content().equals(artifact.content())) {
                    collisions.put(artifact.relativePath(), MyBatisAssistantBundle.message(
                            "generator.error.path.collision"));
                }
            }
        }
        List<MyBatisGenerationPlanEntry> entries = new ArrayList<>();
        List<MyBatisGeneratedArtifact> sortedArtifacts = unique.values().stream()
                .sorted(Comparator.comparing(MyBatisGeneratedArtifact::relativePath))
                .toList();
        for (int index = 0; index < sortedArtifacts.size(); index++) {
            checkCanceled(indicator);
            MyBatisGeneratedArtifact artifact = sortedArtifacts.get(index);
            entries.add(indicator == null
                    ? entry(project, projectRoot, artifact,
                            collisions.get(artifact.relativePath()))
                    : MyBatisReadActionSupport.compute(() -> entry(
                            project,
                            projectRoot,
                            artifact,
                            collisions.get(artifact.relativePath()))));
            updateProgress(indicator, startFraction
                    + (1.0 - startFraction) * (index + 1.0) / sortedArtifacts.size());
        }
        updateProgress(indicator, 1.0);
        return new MyBatisGenerationPlan(entries);
    }

    private static void checkCanceled(@Nullable ProgressIndicator indicator) {
        if (indicator == null) {
            ProgressManager.checkCanceled();
        } else {
            indicator.checkCanceled();
        }
    }

    private static void updateProgress(
            @Nullable ProgressIndicator indicator,
            double fraction) {
        if (indicator != null) {
            indicator.setFraction(fraction);
        }
    }

    private static @NotNull MyBatisGenerationPlanEntry entry(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisGeneratedArtifact artifact,
            String collision) {
        if (collision != null) {
            return conflict(artifact, Optional.empty(),
                    MyBatisSafeMergeConflictCode.PATH_COLLISION, collision);
        }
        ProgressManager.checkCanceled();
        VirtualFile existing = projectRoot.findFileByRelativePath(artifact.relativePath());
        if (existing == null) {
            VirtualFile ancestor = nearestExistingAncestor(projectRoot, artifact.relativePath());
            if (ancestor == null || !ancestor.isDirectory() || !ancestor.isWritable()) {
                return conflict(artifact, Optional.empty(),
                        MyBatisSafeMergeConflictCode.TARGET_READ_ONLY,
                        MyBatisAssistantBundle.message(
                                "generator.error.target.directory.readonly",
                                artifact.relativePath()));
            }
            try {
                MyBatisGenerationPsiValidator.validate(project, artifact, artifact.content());
                return ready(artifact, MyBatisGenerationPlanStatus.CREATE,
                        Optional.empty(), artifact.content());
            } catch (IllegalArgumentException invalid) {
                return conflict(artifact, Optional.empty(),
                        MyBatisSafeMergeConflictCode.INVALID_GENERATED_CONTENT,
                        invalid.getMessage());
            }
        }
        if (existing.isDirectory() || !existing.isWritable()) {
            return conflict(artifact, Optional.empty(),
                    existing.isDirectory()
                            ? MyBatisSafeMergeConflictCode.TARGET_IS_DIRECTORY
                            : MyBatisSafeMergeConflictCode.TARGET_READ_ONLY,
                    existing.isDirectory()
                            ? MyBatisAssistantBundle.message(
                                    "generator.error.target.is.directory",
                                    artifact.relativePath())
                            : MyBatisAssistantBundle.message(
                                    "generator.error.target.file.readonly",
                                    artifact.relativePath()));
        }
        String existingText;
        try {
            com.intellij.openapi.editor.Document document = FileDocumentManager.getInstance()
                    .getCachedDocument(existing);
            existingText = document == null ? VfsUtilCore.loadText(existing) : document.getText();
        } catch (IOException failure) {
            return conflict(artifact, Optional.empty(),
                    MyBatisSafeMergeConflictCode.IO_ERROR,
                    MyBatisAssistantBundle.message(
                            "generator.error.target.file.unreadable",
                            artifact.relativePath()));
        }
        MyBatisSafeMergeResult merged = MyBatisSafeMerger.merge(
                existingText, artifact.content());
        if (merged instanceof MyBatisSafeMergeResult.Conflict conflict) {
            return conflict(artifact, Optional.of(existingText),
                    conflict.code(), conflict.message());
        }
        MyBatisSafeMergeResult.Ready ready = (MyBatisSafeMergeResult.Ready) merged;
        try {
            MyBatisGenerationPsiValidator.validate(project, artifact, ready.text());
        } catch (IllegalArgumentException invalid) {
            return conflict(artifact, Optional.of(existingText),
                    MyBatisSafeMergeConflictCode.INVALID_GENERATED_CONTENT,
                    invalid.getMessage());
        }
        return ready(
                artifact,
                ready.changed()
                        ? MyBatisGenerationPlanStatus.UPDATE
                        : MyBatisGenerationPlanStatus.UNCHANGED,
                Optional.of(existingText),
                ready.text());
    }

    private static @NotNull VirtualFile nearestExistingAncestor(
            @NotNull VirtualFile root,
            @NotNull String relativePath) {
        VirtualFile current = root;
        String[] segments = relativePath.split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            VirtualFile child = current.findChild(segments[index]);
            if (child == null) {
                return current;
            }
            if (!child.isDirectory()) {
                return null;
            }
            current = child;
        }
        return current;
    }

    private static @NotNull MyBatisGenerationPlanEntry ready(
            @NotNull MyBatisGeneratedArtifact artifact,
            @NotNull MyBatisGenerationPlanStatus status,
            @NotNull Optional<String> existing,
            @NotNull String proposed) {
        return new MyBatisGenerationPlanEntry(
                artifact, status, existing, Optional.of(proposed),
                Optional.empty(), Optional.empty());
    }

    private static @NotNull MyBatisGenerationPlanEntry conflict(
            @NotNull MyBatisGeneratedArtifact artifact,
            @NotNull Optional<String> existing,
            @NotNull MyBatisSafeMergeConflictCode code,
            @NotNull String message) {
        return new MyBatisGenerationPlanEntry(
                artifact,
                MyBatisGenerationPlanStatus.CONFLICT,
                existing,
                Optional.empty(),
                Optional.of(code),
                Optional.of(message));
    }
}
