package io.github.ns3154.mybatisassistant.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只监听 IPv4 回环并执行严格请求校验的本地 HTTP 传输。
 */
final class MyBatisMcpHttpServer implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int BACKLOG = 8;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] expectedAuthorization;
    private final MyBatisMcpProtocolHandler protocol;

    private MyBatisMcpHttpServer(
            @NotNull HttpServer server,
            @NotNull ExecutorService executor,
            @NotNull String token,
            @NotNull MyBatisMcpProtocolHandler protocol) {
        this.server = server;
        this.executor = executor;
        this.expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.protocol = protocol;
    }

    static @NotNull MyBatisMcpHttpServer start(
            int configuredPort,
            @NotNull String token,
            @NotNull MyBatisMcpProtocolHandler protocol) throws IOException {
        InetSocketAddress address = new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                configuredPort);
        HttpServer server = HttpServer.create(address, BACKLOG);
        ExecutorService executor = Executors.newFixedThreadPool(2, new McpThreadFactory());
        MyBatisMcpHttpServer transport = new MyBatisMcpHttpServer(
                server, executor, token, protocol);
        server.createContext("/mcp", transport::handle);
        server.setExecutor(executor);
        server.start();
        return transport;
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(@NotNull HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"/mcp".equals(exchange.getRequestURI().getPath())) {
                send(exchange, new MyBatisMcpHttpResponse(404, null, error("not found")));
                return;
            }
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !isLocalHost(exchange.getRequestHeaders().getFirst("Host"))) {
                send(exchange, new MyBatisMcpHttpResponse(403, null, error("forbidden")));
                return;
            }
            if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                send(exchange, new MyBatisMcpHttpResponse(403, null, error("forbidden")));
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, new MyBatisMcpHttpResponse(401, null, error("unauthorized")));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, new MyBatisMcpHttpResponse(405, null, error("method not allowed")));
                return;
            }
            if (!isJson(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                send(exchange, new MyBatisMcpHttpResponse(
                        415, null, error("application/json required")));
                return;
            }
            String body;
            try {
                body = readBody(exchange.getRequestBody());
            } catch (RequestTooLargeException tooLarge) {
                send(exchange, new MyBatisMcpHttpResponse(
                        413, null, error("request too large")));
                return;
            }
            send(exchange, protocol.handle(
                    body,
                    exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
        }
    }

    private boolean isLocalHost(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1:" + port())
                || normalized.equals("localhost:" + port());
    }

    private boolean authorized(String authorization) {
        return authorization != null && MessageDigest.isEqual(
                expectedAuthorization,
                authorization.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isJson(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private static @NotNull String readBody(@NotNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void send(
            @NotNull HttpExchange exchange,
            @NotNull MyBatisMcpHttpResponse response) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("X-Content-Type-Options", "nosniff");
        if (response.sessionId() != null) {
            headers.set("Mcp-Session-Id", response.sessionId());
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.status() == 202 && bytes.length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static @NotNull String error(@NotNull String message) {
        return "{\"error\":\"" + message + "\"}";
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        java.util.Arrays.fill(expectedAuthorization, (byte) 0);
        protocol.close();
    }

    private static final class McpThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "mybatis-assistant-mcp-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class RequestTooLargeException extends IOException {
    }
}
