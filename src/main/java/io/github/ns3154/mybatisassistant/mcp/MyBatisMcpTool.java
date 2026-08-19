package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

interface MyBatisMcpTool {
    @NotNull String name();

    @NotNull String description();

    @NotNull JsonObject inputSchema();

    boolean readOnly();

    @NotNull JsonObject invoke(@NotNull Project project, @NotNull JsonObject arguments);
}
