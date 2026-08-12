package io.github.ns3154.mybatisassistant.mcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationCommandExecutor;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存短期、一次性、项目隔离的写入预览。
 */
final class MyBatisMcpWritePreviewStore implements AutoCloseable {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_PENDING = 64;
    private static final int MAX_PREVIEW_CHARS = 2 * 1024 * 1024;
    private final Project project;
    private final Map<String, PendingPreview> previews = new LinkedHashMap<>();

    MyBatisMcpWritePreviewStore(@NotNull Project project) {
        this.project = project;
    }

    synchronized @NotNull String put(
            @NotNull String operation,
            @NotNull MyBatisGenerationPlan plan) {
        cleanupExpired();
        if (previews.size() >= MAX_PENDING) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.preview.limit"));
        }
        if (!plan.hasChanges() || plan.hasConflicts()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.preview.no.safe.change"));
        }
        long characters = plan.entries().stream()
                .mapToLong(entry -> entry.proposedText().map(String::length).orElse(0)
                        + entry.existingText().map(String::length).orElse(0))
                .sum();
        if (characters > MAX_PREVIEW_CHARS) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.preview.too.large"));
        }
        String token = MyBatisMcpProtocolHandler.randomToken(24);
        previews.put(token, new PendingPreview(operation, plan, System.nanoTime()));
        return token;
    }

    @NotNull Confirmation confirm(@NotNull String token) {
        PendingPreview preview;
        synchronized (this) {
            cleanupExpired();
            preview = previews.remove(token);
        }
        if (preview == null) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.preview.token.invalid"));
        }
        VirtualFile projectRoot = ProjectUtil.guessProjectDir(project);
        if (projectRoot == null || !projectRoot.isValid()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.project.root.unavailable"));
        }
        RuntimeException[] failure = new RuntimeException[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                MyBatisGenerationCommandExecutor.execute(
                        project,
                        projectRoot,
                        preview.plan,
                        MyBatisAssistantBundle.message(
                                "mcp.command.confirm", preview.operation));
            } catch (RuntimeException problem) {
                failure[0] = problem;
            }
        });
        if (failure[0] != null) {
            throw new MyBatisMcpToolException(failure[0].getMessage() == null
                    ? MyBatisAssistantBundle.message("mcp.error.write.stopped")
                    : failure[0].getMessage());
        }
        return new Confirmation(
                preview.operation,
                preview.plan.entries().stream()
                        .filter(entry -> entry.status() != MyBatisGenerationPlanStatus.UNCHANGED)
                        .map(entry -> entry.artifact().relativePath())
                        .toList());
    }

    synchronized int size() {
        cleanupExpired();
        return previews.size();
    }

    private void cleanupExpired() {
        long now = System.nanoTime();
        previews.entrySet().removeIf(
                entry -> now - entry.getValue().createdAt > TTL.toNanos());
    }

    @Override
    public synchronized void close() {
        previews.clear();
    }

    record Confirmation(@NotNull String operation, @NotNull java.util.List<String> paths) {
        Confirmation {
            paths = java.util.List.copyOf(paths);
        }
    }

    private record PendingPreview(
            @NotNull String operation,
            @NotNull MyBatisGenerationPlan plan,
            long createdAt) {
    }
}
