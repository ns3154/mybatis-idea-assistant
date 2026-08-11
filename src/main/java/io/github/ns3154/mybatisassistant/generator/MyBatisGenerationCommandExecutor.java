package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 在一个稳定的 IDE 命令中原子写入已预览的生成计划。
 */
public final class MyBatisGenerationCommandExecutor {
    private static final String COMMAND_NAME = "生成 MyBatis 代码";

    private MyBatisGenerationCommandExecutor() {
    }

    /**
     * 执行前在写锁内复核全部目标；任一写入失败时恢复已更新文件并删除本批新建项。
     */
    public static void execute(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisGenerationPlan plan) {
        execute(project, projectRoot, plan, WriteHook.NONE);
    }

    static void execute(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisGenerationPlan plan,
            @NotNull WriteHook writeHook) {
        Objects.requireNonNull(writeHook, "writeHook");
        rejectConflicts(plan);
        List<Document> changedDocuments = new ArrayList<>();
        Object commandGroupId = new Object();
        CommandProcessor.getInstance().executeCommand(
                project,
                () -> {
                    CommandProcessor.getInstance().markCurrentCommandAsGlobal(project);
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        String preflightError = preflight(projectRoot, plan);
                        if (preflightError != null) {
                            throw new IllegalStateException(preflightError);
                        }
                        WriteSession session = new WriteSession(
                                projectRoot, changedDocuments, writeHook);
                        try {
                            session.apply(plan);
                        } catch (IOException | RuntimeException failure) {
                            throw session.rollbackAndDescribe(failure);
                        }
                    });
                },
                COMMAND_NAME,
                commandGroupId);
        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        changedDocuments.stream().distinct().forEach(documentManager::saveDocument);
    }

    private static void rejectConflicts(@NotNull MyBatisGenerationPlan plan) {
        if (!plan.hasConflicts()) {
            return;
        }
        throw new IllegalStateException(plan.entries().stream()
                .filter(entry -> entry.status() == MyBatisGenerationPlanStatus.CONFLICT)
                .map(entry -> entry.artifact().relativePath() + "："
                        + entry.message().orElse("生成冲突"))
                .collect(Collectors.joining("\n")));
    }

    private static String preflight(
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisGenerationPlan plan) {
        if (!projectRoot.isValid() || !projectRoot.isDirectory()
                || !projectRoot.isWritable()) {
            return "项目目录已失效或变为只读，请重新预览";
        }
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            ProgressManager.checkCanceled();
            if (entry.status() == MyBatisGenerationPlanStatus.UNCHANGED) {
                continue;
            }
            String relativePath = entry.artifact().relativePath();
            if (!isSafeRelativePath(relativePath)) {
                return "目标路径不是安全的项目相对路径：" + relativePath;
            }
            VirtualFile current = projectRoot.findFileByRelativePath(relativePath);
            if (entry.status() == MyBatisGenerationPlanStatus.CREATE) {
                if (current != null) {
                    return "预览后目标已被创建，请重新预览：" + relativePath;
                }
                VirtualFile parent = nearestExistingAncestor(projectRoot, relativePath);
                if (parent == null || !parent.isDirectory() || !parent.isWritable()) {
                    return "预览后目标目录已失效或变为只读：" + relativePath;
                }
                continue;
            }
            if (current == null || current.isDirectory() || !current.isWritable()) {
                return "预览后目标已失效或变为只读：" + relativePath;
            }
            try {
                Document document = FileDocumentManager.getInstance()
                        .getCachedDocument(current);
                String currentText = document == null
                        ? VfsUtilCore.loadText(current)
                        : document.getText();
                if (!currentText.equals(entry.existingText().orElseThrow())) {
                    return "预览后文件内容已变化，请重新预览：" + relativePath;
                }
            } catch (IOException failure) {
                return "预览后文件无法读取：" + relativePath;
            }
        }
        return null;
    }

    private static boolean isSafeRelativePath(@NotNull String relativePath) {
        if (relativePath.isBlank() || relativePath.startsWith("/")
                || relativePath.endsWith("/") || relativePath.contains("\\")) {
            return false;
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static VirtualFile nearestExistingAncestor(
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

    @FunctionalInterface
    interface WriteHook {
        WriteHook NONE = (relativePath, completedWrites) -> {
        };

        void afterWrite(@NotNull String relativePath, int completedWrites) throws IOException;
    }

    private static final class WriteSession {
        private final VirtualFile projectRoot;
        private final List<Document> changedDocuments;
        private final WriteHook writeHook;
        private final List<UpdatedDocument> updatedDocuments = new ArrayList<>();
        private final List<VirtualFile> createdFiles = new ArrayList<>();
        private final List<VirtualFile> createdDirectories = new ArrayList<>();
        private int completedWrites;

        private WriteSession(
                @NotNull VirtualFile projectRoot,
                @NotNull List<Document> changedDocuments,
                @NotNull WriteHook writeHook) {
            this.projectRoot = projectRoot;
            this.changedDocuments = changedDocuments;
            this.writeHook = writeHook;
        }

        private void apply(@NotNull MyBatisGenerationPlan plan) throws IOException {
            for (MyBatisGenerationPlanEntry entry : plan.entries()) {
                ProgressManager.checkCanceled();
                if (entry.status() == MyBatisGenerationPlanStatus.UNCHANGED) {
                    continue;
                }
                if (entry.status() == MyBatisGenerationPlanStatus.UPDATE) {
                    update(entry);
                } else {
                    create(entry);
                }
                completedWrites++;
                writeHook.afterWrite(entry.artifact().relativePath(), completedWrites);
            }
        }

        private void update(@NotNull MyBatisGenerationPlanEntry entry) throws IOException {
            String relativePath = entry.artifact().relativePath();
            VirtualFile file = projectRoot.findFileByRelativePath(relativePath);
            if (file == null) {
                throw new IOException("目标文件在写入时失效：" + relativePath);
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) {
                throw new IOException("无法取得目标文档：" + relativePath);
            }
            String oldText = entry.existingText().orElseThrow();
            updatedDocuments.add(new UpdatedDocument(document, oldText));
            document.setText(entry.proposedText().orElseThrow());
            changedDocuments.add(document);
        }

        private void create(@NotNull MyBatisGenerationPlanEntry entry) throws IOException {
            String relativePath = entry.artifact().relativePath();
            String[] segments = relativePath.split("/");
            VirtualFile parent = projectRoot;
            for (int index = 0; index < segments.length - 1; index++) {
                VirtualFile child = parent.findChild(segments[index]);
                if (child == null) {
                    child = parent.createChildDirectory(this, segments[index]);
                    createdDirectories.add(child);
                } else if (!child.isDirectory()) {
                    throw new IOException("目标父路径已变为文件：" + relativePath);
                }
                parent = child;
            }
            VirtualFile file = parent.createChildData(this, segments[segments.length - 1]);
            createdFiles.add(file);
            VfsUtil.saveText(file, entry.proposedText().orElseThrow());
        }

        private RuntimeException rollbackAndDescribe(@NotNull Exception failure) {
            RuntimeException rollbackFailure = rollback();
            if (rollbackFailure != null) {
                IllegalStateException result = new IllegalStateException(
                        "生成写入失败且自动回滚不完整，请使用“MyBatis Assistant 代码生成前”"
                                + " Local History 标签恢复：" + message(failure),
                        failure);
                result.addSuppressed(rollbackFailure);
                return result;
            }
            changedDocuments.clear();
            if (failure instanceof ProcessCanceledException canceled) {
                return canceled;
            }
            return new IllegalStateException(
                    "生成写入失败，已自动回滚：" + message(failure), failure);
        }

        private RuntimeException rollback() {
            RuntimeException result = null;
            List<UpdatedDocument> updates = new ArrayList<>(updatedDocuments);
            Collections.reverse(updates);
            for (UpdatedDocument update : updates) {
                try {
                    update.document().setText(update.oldText());
                } catch (RuntimeException failure) {
                    result = appendFailure(result, failure);
                }
            }
            List<VirtualFile> files = new ArrayList<>(createdFiles);
            Collections.reverse(files);
            for (VirtualFile file : files) {
                try {
                    if (file.isValid()) {
                        file.delete(this);
                    }
                } catch (IOException | RuntimeException failure) {
                    result = appendFailure(result, failure);
                }
            }
            List<VirtualFile> directories = new ArrayList<>(createdDirectories);
            Collections.reverse(directories);
            for (VirtualFile directory : directories) {
                try {
                    if (directory.isValid() && directory.getChildren().length == 0) {
                        directory.delete(this);
                    }
                } catch (IOException | RuntimeException failure) {
                    result = appendFailure(result, failure);
                }
            }
            return result;
        }

        private static @NotNull RuntimeException appendFailure(
                RuntimeException current,
                @NotNull Exception failure) {
            RuntimeException converted = failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(failure);
            if (current == null) {
                return converted;
            }
            current.addSuppressed(converted);
            return current;
        }

        private static @NotNull String message(@NotNull Exception failure) {
            return failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
        }
    }

    private record UpdatedDocument(@NotNull Document document, @NotNull String oldText) {
    }
}
