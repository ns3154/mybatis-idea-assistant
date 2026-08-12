package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * 仅在用户明确操作时把当前项目的一次性 MCP 连接配置放入剪贴板。
 */
public final class MyBatisMcpCopyConnectionAction extends AnAction {
    private static final Gson GSON = new Gson();

    public MyBatisMcpCopyConnectionAction() {
        super(
                MyBatisAssistantBundle.message("action.mcp.copy.connection.text"),
                MyBatisAssistantBundle.message("action.mcp.copy.connection.description"),
                null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        var endpoint = MyBatisMcpProjectService.getInstance(project).endpoint();
        if (endpoint.isEmpty()) {
            notify(project, NotificationType.WARNING,
                    "notification.mcp.disabled.title",
                    "notification.mcp.disabled.content");
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(
                connectionConfiguration(endpoint.orElseThrow())));
        notify(project, NotificationType.INFORMATION,
                "notification.mcp.copied.title",
                "notification.mcp.copied.content");
    }

    static @NotNull String connectionConfiguration(
            @NotNull MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint) {
        JsonObject configuration = new JsonObject();
        configuration.addProperty("transport", "streamable-http");
        configuration.addProperty("url", endpoint.url());
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + endpoint.accessToken());
        configuration.add("headers", headers);
        configuration.addProperty("protocolVersion", MyBatisMcpProtocolHandler.PROTOCOL_VERSION);
        return GSON.toJson(configuration);
    }

    private static void notify(
            @NotNull Project project,
            @NotNull NotificationType type,
            @NotNull String titleKey,
            @NotNull String contentKey) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MyBatis Assistant")
                .createNotification(
                        MyBatisAssistantBundle.message(titleKey),
                        MyBatisAssistantBundle.message(contentKey),
                        type)
                .notify(project);
    }
}
