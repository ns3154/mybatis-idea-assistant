package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界 JSON-RPC MCP 协议处理器；不持有访问令牌。
 */
final class MyBatisMcpProtocolHandler implements AutoCloseable {
    private static final String VERSION_RESOURCE =
            "/META-INF/mybatis-assistant-version.properties";
    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int MAX_SESSIONS = 64;
    private static final Gson GSON = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final MyBatisMcpToolRegistry tools;
    private final AutoCloseable lifecycle;

    MyBatisMcpProtocolHandler(
            @NotNull MyBatisMcpToolRegistry tools,
            @NotNull AutoCloseable lifecycle) {
        this.tools = tools;
        this.lifecycle = lifecycle;
    }

    @NotNull MyBatisMcpHttpResponse handle(
            @NotNull String body,
            @Nullable String sessionId) {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return rpcError(null, -32600, "invalid request", 400);
            }
            request = parsed.getAsJsonObject();
        } catch (JsonParseException failure) {
            return rpcError(null, -32700, "parse error", 400);
        }
        JsonElement id = request.get("id");
        if (!"2.0".equals(string(request, "jsonrpc"))) {
            return rpcError(id, -32600, "invalid request", 400);
        }
        String method = string(request, "method");
        if (method == null || method.isBlank()) {
            return rpcError(id, -32600, "invalid request", 400);
        }
        JsonObject params = object(request, "params");
        if ("initialize".equals(method)) {
            return initialize(id, params);
        }
        if (!validSession(sessionId)) {
            return rpcError(id, -32001, "invalid or expired session", 404);
        }
        touch(sessionId);
        return switch (method) {
            case "notifications/initialized" -> new MyBatisMcpHttpResponse(202, null, "");
            case "ping" -> rpcResult(id, new JsonObject(), null);
            case "tools/list" -> toolsList(id);
            case "tools/call" -> toolCall(id, params);
            default -> rpcError(id, -32601, "method not found", 200);
        };
    }

    private @NotNull MyBatisMcpHttpResponse initialize(
            @Nullable JsonElement id,
            @NotNull JsonObject params) {
        cleanupExpired();
        if (sessions.size() >= MAX_SESSIONS) {
            return rpcError(id, -32002, "session limit reached", 429);
        }
        String requestedVersion = string(params, "protocolVersion");
        if (requestedVersion != null
                && !PROTOCOL_VERSION.equals(requestedVersion)
                && !"2025-03-26".equals(requestedVersion)) {
            return rpcError(id, -32602, "unsupported protocol version", 200);
        }
        String sessionId = randomToken(24);
        sessions.put(sessionId, System.nanoTime());
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "mybatis-idea-assistant");
        serverInfo.addProperty("version", pluginVersion());
        result.add("serverInfo", serverInfo);
        return rpcResult(id, result, sessionId);
    }

    static @NotNull String pluginVersion() {
        try (InputStream input = MyBatisMcpProtocolHandler.class.getResourceAsStream(
                VERSION_RESOURCE)) {
            if (input == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version", "").trim();
            return version.isEmpty() ? "unknown" : version;
        } catch (IOException ignored) {
            return "unknown";
        }
    }

    private @NotNull MyBatisMcpHttpResponse toolsList(@Nullable JsonElement id) {
        JsonObject result = new JsonObject();
        result.add("tools", tools.definitions());
        return rpcResult(id, result, null);
    }

    private @NotNull MyBatisMcpHttpResponse toolCall(
            @Nullable JsonElement id,
            @NotNull JsonObject params) {
        String name = string(params, "name");
        if (name == null || name.isBlank()) {
            return rpcError(id, -32602, "tool name required", 200);
        }
        JsonObject arguments = object(params, "arguments");
        try {
            JsonObject structured = tools.invoke(name, arguments);
            JsonObject result = new JsonObject();
            com.google.gson.JsonArray content = new com.google.gson.JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", GSON.toJson(structured));
            content.add(text);
            result.add("content", content);
            result.add("structuredContent", structured);
            result.addProperty("isError", false);
            return rpcResult(id, result, null);
        } catch (MyBatisMcpToolException failure) {
            return toolError(id, failure.getMessage());
        } catch (RuntimeException failure) {
            return toolError(id, MyBatisAssistantBundle.message(
                    "mcp.error.tool.stopped"));
        }
    }

    private static @NotNull MyBatisMcpHttpResponse toolError(
            @Nullable JsonElement id,
            @NotNull String message) {
        JsonObject result = new JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message);
        content.add(text);
        result.add("content", content);
        result.addProperty("isError", true);
        return rpcResult(id, result, null);
    }

    private boolean validSession(@Nullable String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Long lastSeen = sessions.get(sessionId);
        return lastSeen != null
                && System.nanoTime() - lastSeen <= SESSION_TTL.toNanos();
    }

    private void touch(@NotNull String sessionId) {
        sessions.computeIfPresent(sessionId, (ignored, timestamp) -> System.nanoTime());
    }

    private void cleanupExpired() {
        long now = System.nanoTime();
        sessions.entrySet().removeIf(entry -> now - entry.getValue() > SESSION_TTL.toNanos());
    }

    static @NotNull String randomToken(int bytes) {
        if (bytes < 24) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "mcp.error.token.entropy"));
        }
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        java.util.Arrays.fill(value, (byte) 0);
        return encoded;
    }

    private static @NotNull MyBatisMcpHttpResponse rpcResult(
            @Nullable JsonElement id,
            @NotNull JsonObject result,
            @Nullable String sessionId) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", copyId(id));
        response.add("result", result);
        return new MyBatisMcpHttpResponse(200, sessionId, GSON.toJson(response));
    }

    private static @NotNull MyBatisMcpHttpResponse rpcError(
            @Nullable JsonElement id,
            int code,
            @NotNull String message,
            int httpStatus) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", copyId(id));
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return new MyBatisMcpHttpResponse(httpStatus, null, GSON.toJson(response));
    }

    private static @NotNull JsonElement copyId(@Nullable JsonElement id) {
        return id == null || id.isJsonNull()
                ? com.google.gson.JsonNull.INSTANCE
                : id.deepCopy();
    }

    private static @Nullable String string(
            @NotNull JsonObject object,
            @NotNull String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive()
                ? null
                : value.getAsString();
    }

    private static @NotNull JsonObject object(
            @NotNull JsonObject parent,
            @NotNull String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject()
                ? value.getAsJsonObject()
                : new JsonObject();
    }

    @Override
    public void close() {
        sessions.clear();
        try {
            lifecycle.close();
        } catch (Exception ignored) {
            // 生命周期清理不向关闭调用方抛出；底层存储只执行内存清空。
        }
    }
}
