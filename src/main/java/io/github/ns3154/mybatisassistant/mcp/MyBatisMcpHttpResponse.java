package io.github.ns3154.mybatisassistant.mcp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

record MyBatisMcpHttpResponse(
        int status,
        @Nullable String sessionId,
        @NotNull String body) {
}
