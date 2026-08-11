package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次读取全部目标并在写入前计算新建、更新、无变化与冲突。
 */
public final class MyBatisGenerationPlanner {
    private MyBatisGenerationPlanner() {
    }

    public static @NotNull MyBatisGenerationPlan plan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull List<MyBatisGenerationBundle> bundles) {
        if (!projectRoot.isDirectory()) {
            throw new IllegalArgumentException("生成基目录必须是项目目录");
        }
        Map<String, MyBatisGeneratedArtifact> unique = new LinkedHashMap<>();
        Map<String, String> collisions = new LinkedHashMap<>();
        for (MyBatisGenerationBundle bundle : bundles) {
            for (MyBatisGeneratedArtifact artifact : bundle.artifacts()) {
                ProgressManager.checkCanceled();
                MyBatisGeneratedArtifact previous = unique.putIfAbsent(
                        artifact.relativePath(), artifact);
                if (previous != null && !previous.content().equals(artifact.content())) {
                    collisions.put(artifact.relativePath(),
                            "多张表产生了同一目标路径");
                }
            }
        }
        List<MyBatisGenerationPlanEntry> entries = new ArrayList<>();
        unique.values().stream()
                .sorted(Comparator.comparing(MyBatisGeneratedArtifact::relativePath))
                .forEach(artifact -> entries.add(entry(
                        project, projectRoot, artifact, collisions.get(artifact.relativePath()))));
        return new MyBatisGenerationPlan(entries);
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
                        "目标目录不可写：" + artifact.relativePath());
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
                            ? "目标路径已是目录：" + artifact.relativePath()
                            : "目标文件只读：" + artifact.relativePath());
        }
        String existingText;
        try {
            com.intellij.openapi.editor.Document document = FileDocumentManager.getInstance()
                    .getCachedDocument(existing);
            existingText = document == null ? VfsUtilCore.loadText(existing) : document.getText();
        } catch (IOException failure) {
            return conflict(artifact, Optional.empty(),
                    MyBatisSafeMergeConflictCode.IO_ERROR,
                    "无法读取目标文件：" + artifact.relativePath());
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
