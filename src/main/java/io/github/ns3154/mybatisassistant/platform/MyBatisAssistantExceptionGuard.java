package io.github.ns3154.mybatisassistant.platform;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class MyBatisAssistantExceptionGuard {
    private static final Logger LOG = Logger.getInstance(MyBatisAssistantExceptionGuard.class);
    private static final Pattern SAFE_OPERATION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private MyBatisAssistantExceptionGuard() {
    }

    public static boolean run(
            @NotNull Project project,
            @NotNull String operationId,
            @NotNull Runnable operation
    ) {
        try {
            operation.run();
            return true;
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            String safeOperationId = SAFE_OPERATION_ID.matcher(operationId).matches() ? operationId : "unknown";
            LOG.warn("MyBatis Assistant 操作失败：" + safeOperationId
                    + "，异常类型：" + failure.getClass().getName());
            MyBatisAssistantNotifications.notifyUnexpectedError(project);
            return false;
        }
    }
}
