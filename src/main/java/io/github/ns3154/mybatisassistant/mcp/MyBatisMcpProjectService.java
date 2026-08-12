package io.github.ns3154.mybatisassistant.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettingsListener;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Optional;

/**
 * 管理单个项目的 MCP listener、令牌、会话和关闭生命周期。
 */
public final class MyBatisMcpProjectService implements Disposable {
    private final Project project;
    private final MessageBusConnection settingsConnection;
    private MyBatisMcpHttpServer server;
    private char[] accessToken;
    private int configuredPort = -1;
    private String lastFailure;

    public MyBatisMcpProjectService(@NotNull Project project) {
        this.project = project;
        settingsConnection = ApplicationManager.getApplication()
                .getMessageBus()
                .connect(this);
        settingsConnection.subscribe(
                MyBatisAssistantSettingsListener.TOPIC,
                state -> reconcile());
    }

    public static @NotNull MyBatisMcpProjectService getInstance(@NotNull Project project) {
        return project.getService(MyBatisMcpProjectService.class);
    }

    public synchronized void reconcile() {
        if (project.isDisposed() || !project.isOpen()) {
            stop();
            return;
        }
        MyBatisAssistantSettings settings = MyBatisAssistantSettings.getInstance();
        if (!settings.isMcpEnabled()) {
            stop();
            return;
        }
        int desiredPort = settings.getMcpPort();
        if (server != null) {
            if (configuredPort == desiredPort) {
                return;
            }
            stop();
        }
        String token = MyBatisMcpProtocolHandler.randomToken(32);
        MyBatisMcpWritePreviewStore previewStore = new MyBatisMcpWritePreviewStore(project);
        java.util.List<MyBatisMcpTool> tools = new java.util.ArrayList<>(
                MyBatisMcpReadTools.all());
        tools.addAll(MyBatisMcpWriteTools.all(previewStore));
        MyBatisMcpProtocolHandler protocol = new MyBatisMcpProtocolHandler(
                new MyBatisMcpToolRegistry(project, tools),
                previewStore);
        try {
            server = MyBatisMcpHttpServer.start(desiredPort, token, protocol);
            accessToken = token.toCharArray();
            configuredPort = desiredPort;
            lastFailure = null;
        } catch (IOException | RuntimeException failure) {
            protocol.close();
            clearToken();
            configuredPort = -1;
            lastFailure = MyBatisAssistantBundle.message(
                    "mcp.error.server.start", failure.getClass().getSimpleName());
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized @NotNull Optional<MyBatisMcpEndpoint> endpoint() {
        if (server == null || accessToken == null) {
            return Optional.empty();
        }
        return Optional.of(new MyBatisMcpEndpoint(
                "http://127.0.0.1:" + server.port() + "/mcp",
                new String(accessToken)));
    }

    public synchronized @NotNull Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
        configuredPort = -1;
        lastFailure = null;
        clearToken();
    }

    private void clearToken() {
        if (accessToken != null) {
            java.util.Arrays.fill(accessToken, '\0');
            accessToken = null;
        }
    }

    @Override
    public void dispose() {
        stop();
    }

    public record MyBatisMcpEndpoint(@NotNull String url, @NotNull String accessToken) {
    }
}
