package io.github.ns3154.mybatisassistant.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * 项目启动后仅按默认关闭的设置协调 MCP 服务。
 */
public final class MyBatisMcpStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        MyBatisMcpProjectService.getInstance(project).reconcile();
    }
}
