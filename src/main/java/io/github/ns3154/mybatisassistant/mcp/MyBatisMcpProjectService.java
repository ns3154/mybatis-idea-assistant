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
    private String lastFailureType;

    public MyBatisMcpProjectService(@NotNull Project project) {
        this.project = project;
        settingsConnection = ApplicationManager.getApplication()
                .getMessageBus()
                .connect(this);
        // loadState 会同步发布状态，监听器必须直接消费快照，避免重入初始化应用级设置服务。
        settingsConnection.subscribe(
                MyBatisAssistantSettingsListener.TOPIC,
                this::reconcile);
    }

    public static @NotNull MyBatisMcpProjectService getInstance(@NotNull Project project) {
        return project.getService(MyBatisMcpProjectService.class);
    }

    public void reconcile() {
        reconcile(MyBatisAssistantSettings.getInstance().getState());
    }

    private synchronized void reconcile(
            @NotNull MyBatisAssistantSettings.SettingsState settingsState) {
        if (project.isDisposed() || !project.isOpen()) {
            stop();
            return;
        }
        MyBatisAssistantSettings.SettingsState settings = settingsState.copyAndNormalize();
        if (!settings.mcpEnabled) {
            stop();
            return;
        }
        int desiredPort = settings.mcpPort;
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
            lastFailureType = null;
        } catch (IOException | RuntimeException failure) {
            protocol.close();
            clearToken();
            configuredPort = -1;
            // loadState 会同步发布设置事件；此处只记录稳定类型，避免失败路径为本地化
            // 文案再次读取正在初始化的应用级设置服务。文案在调用方读取时再生成。
            lastFailureType = failure.getClass().getSimpleName();
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
        return Optional.ofNullable(lastFailureType).map(type ->
                MyBatisAssistantBundle.message("mcp.error.server.start", type));
    }

    public synchronized void stop() {
        if (server != null) {
            server.close();
            server = null;
        }
        configuredPort = -1;
        lastFailureType = null;
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
