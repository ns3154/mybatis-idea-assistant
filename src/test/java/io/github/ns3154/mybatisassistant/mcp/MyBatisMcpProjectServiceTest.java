package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataResult;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceConfig;
import io.github.ns3154.mybatisassistant.database.jdbc.MyBatisJdbcDataSourceManager;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class MyBatisMcpProjectServiceTest extends BasePlatformTestCase {
    private MyBatisAssistantSettings.SettingsState originalSettings;
    private MyBatisMcpProjectService service;

    @Override
    protected boolean runInDispatchThread() {
        return false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalSettings = MyBatisAssistantSettings.getInstance().getState();
        service = MyBatisMcpProjectService.getInstance(getProject());
        service.stop();
        MyBatisAssistantSettings.getInstance().resetToDefaults();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.stop();
            MyBatisAssistantSettings.getInstance().replace(originalSettings);
        } finally {
            super.tearDown();
        }
    }

    public void testDefaultStateCreatesNoListener() {
        service.reconcile();

        assertFalse(service.isRunning());
        assertTrue(service.endpoint().isEmpty());
    }

    public void testExplicitConnectionConfigurationContainsOnlyEndpointAndCurrentToken() {
        var endpoint = new MyBatisMcpProjectService.MyBatisMcpEndpoint(
                "http://127.0.0.1:12345/mcp", "temporary-secret");
        JsonObject configuration = JsonParser.parseString(
                MyBatisMcpCopyConnectionAction.connectionConfiguration(endpoint))
                .getAsJsonObject();

        assertEquals("streamable-http", configuration.get("transport").getAsString());
        assertEquals(endpoint.url(), configuration.get("url").getAsString());
        assertEquals("Bearer temporary-secret", configuration.getAsJsonObject("headers")
                .get("Authorization").getAsString());
        assertEquals(MyBatisMcpProtocolHandler.PROTOCOL_VERSION,
                configuration.get("protocolVersion").getAsString());
        assertEquals(4, configuration.size());
    }

    public void testLoopbackTransportRequiresTokenJsonPostAndSessionAndReleasesPort()
            throws Exception {
        enableMcp();
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        URI uri = URI.create(endpoint.url());
        assertEquals("127.0.0.1", uri.getHost());

        assertEquals(401, request(endpoint, "POST", "{}", Map.of()).status);
        assertEquals(405, request(endpoint, "GET", "", auth(endpoint)).status);
        int originStatus = request(endpoint, "POST", "{}", headers(
                endpoint, "Origin", "https://attacker.example")).status;
        assertTrue(originStatus == 400 || originStatus == 403);
        String poisonedHostRequest = "POST /mcp HTTP/1.1\r\n"
                + "Host: attacker.example\r\n"
                + "Authorization: Bearer " + endpoint.accessToken() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: 2\r\n"
                + "Connection: close\r\n\r\n{}";
        String poisonedHostStatus = rawStatus(uri.getPort(), poisonedHostRequest);
        assertTrue(poisonedHostStatus, poisonedHostStatus.contains(" 400 ")
                || poisonedHostStatus.contains(" 403 "));
        assertEquals(415, request(endpoint, "POST", "{}", headers(
                endpoint, "Content-Type", "text/plain")).status);
        assertEquals(400, request(endpoint, "POST", "{", auth(endpoint)).status);

        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        assertEquals(200, initialized.status);
        assertNotNull(initialized.sessionId);
        assertEquals(MyBatisMcpProtocolHandler.PROTOCOL_VERSION,
                json(initialized).getAsJsonObject("result").get("protocolVersion").getAsString());

        assertEquals(404, request(endpoint, "POST", rpc(2, "tools/list", "{}"),
                auth(endpoint)).status);
        Response listed = request(endpoint, "POST", rpc(3, "tools/list", "{}"),
                session(endpoint, initialized.sessionId));
        assertEquals(200, listed.status);
        assertTrue(json(listed).getAsJsonObject("result").getAsJsonArray("tools").size() > 0);
        assertFalse(MyBatisAssistantSettingsCodecProbe.exportedSettings()
                .contains(endpoint.accessToken()));

        int port = uri.getPort();
        service.stop();
        assertFalse(service.isRunning());
        assertTrue(service.endpoint().isEmpty());
        try (ServerSocket rebound = new ServerSocket(
                port, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, rebound.getLocalPort());
        }
    }

    public void testReadToolsUseRealMapperModelAndReturnProjectRelativeReferences()
            throws Exception {
        myFixture.addFileToProject("src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    User findById(long id);
                }
                record User(long id, String name) {}
                """);
        myFixture.addFileToProject("src/com/example/Usage.java", """
                package com.example;
                final class Usage {
                    User load(UserMapper mapper) { return mapper.findById(1L); }
                }
                """);
        myFixture.addFileToProject("resources/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                  <select id="findById" resultType="com.example.User">
                    select id, name from users where id = #{id}
                  </select>
                </mapper>
                """);
        commitAllDocuments();
        DumbService.getInstance(getProject()).waitForSmartMode();
        enableMcp();
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        Map<String, String> session = session(endpoint, initialized.sessionId);

        JsonObject mapper = call(endpoint, session, 10, "mapper.list", """
                {"qualifiedName":"com.example.UserMapper"}
                """);
        assertEquals("com.example.UserMapper", mapper.get("qualifiedName").getAsString());
        assertEquals("findById(long)", mapper.getAsJsonArray("methods")
                .get(0).getAsJsonObject().get("signature").getAsString());

        JsonObject parameters = call(endpoint, session, 11, "parameter.describe", """
                {"qualifiedName":"com.example.UserMapper","signature":"findById(long)"}
                """);
        assertEquals("long", parameters.getAsJsonArray("parameters")
                .get(0).getAsJsonObject().get("type").getAsString());

        JsonObject statements = call(endpoint, session, 13, "statement.list", """
                {"qualifiedName":"com.example.UserMapper","limit":1}
                """);
        assertEquals(1, statements.get("total").getAsInt());
        assertFalse(statements.get("truncated").getAsBoolean());
        assertEquals("findById(long)", statements.getAsJsonArray("statements")
                .get(0).getAsJsonObject().get("signature").getAsString());

        JsonObject references = call(endpoint, session, 12, "reference.find", """
                {"qualifiedName":"com.example.UserMapper","signature":"findById(long)","limit":10}
                """);
        assertTrue(references.getAsJsonArray("references").size() >= 1);
        for (var reference : references.getAsJsonArray("references")) {
            String path = reference.getAsJsonObject().get("projectRelativePath").getAsString();
            assertFalse(path.startsWith("/"));
            assertFalse(path.contains(getProject().getLocationHash()));
        }
    }

    public void testWriteToolsStayHiddenWithoutIndependentEnablement() throws Exception {
        MyBatisAssistantSettings.SettingsState state = enabledState();
        state.mcpAllowedTools = java.util.List.of(
                "generation.preview_test", "mapper.list");
        state.mcpWriteToolsEnabled = false;
        MyBatisAssistantSettings.getInstance().replace(state);
        service.reconcile();
        var endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        JsonObject listed = json(request(endpoint, "POST", rpc(30, "tools/list", "{}"),
                session(endpoint, initialized.sessionId))).getAsJsonObject("result");

        assertEquals(1, listed.getAsJsonArray("tools").size());
        assertEquals("mapper.list", listed.getAsJsonArray("tools")
                .get(0).getAsJsonObject().get("name").getAsString());

        state.mcpWriteToolsEnabled = true;
        MyBatisAssistantSettings.getInstance().replace(state);
        JsonObject enabled = json(request(endpoint, "POST", rpc(31, "tools/list", "{}"),
                session(endpoint, initialized.sessionId))).getAsJsonObject("result");
        assertEquals(2, enabled.getAsJsonArray("tools").size());
        assertEquals("generation.preview_test", enabled.getAsJsonArray("tools")
                .get(0).getAsJsonObject().get("name").getAsString());
    }

    public void testPortChangeRotatesTokenAndOccupiedPortFailsClosed() throws Exception {
        enableMcp();
        MyBatisMcpProjectService.MyBatisMcpEndpoint first = service.endpoint().orElseThrow();
        int fixedPort;
        try (ServerSocket available = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            fixedPort = available.getLocalPort();
        }
        MyBatisAssistantSettings.SettingsState changed = enabledState();
        changed.mcpPort = fixedPort;
        MyBatisAssistantSettings.getInstance().replace(changed);

        MyBatisMcpProjectService.MyBatisMcpEndpoint second = service.endpoint().orElseThrow();
        assertEquals(fixedPort, URI.create(second.url()).getPort());
        assertFalse(first.accessToken().equals(second.accessToken()));
        var oldCredentials = new MyBatisMcpProjectService.MyBatisMcpEndpoint(
                second.url(), first.accessToken());
        assertEquals(401, request(oldCredentials, "POST", "{}", auth(oldCredentials)).status);

        service.stop();
        try (ServerSocket occupied = new ServerSocket(
                fixedPort, 1, InetAddress.getByName("127.0.0.1"))) {
            MyBatisAssistantSettings.getInstance().replace(changed);
            assertFalse(service.isRunning());
            assertTrue(service.endpoint().isEmpty());
            assertTrue(service.lastFailure().orElseThrow().startsWith("本地 MCP 服务启动失败"));
        }
        service.reconcile();
        assertTrue(service.isRunning());
        assertTrue(service.lastFailure().isEmpty());
    }

    public void testStatementWriteRequiresPreviewConfirmSupportsUndoAndRejectsDrift()
            throws Exception {
        PsiFile mapperXml = myFixture.addFileToProject("resources/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        EdtTestUtil.runInEdtAndWait(() ->
                myFixture.configureFromExistingVirtualFile(mapperXml.getVirtualFile()));
        commitAllDocuments();
        DumbService.getInstance(getProject()).waitForSmartMode();
        enableWriteMcp("generation.confirm", "generation.preview_statement");
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        Map<String, String> session = session(endpoint, initialized.sessionId);
        String original = documentText(mapperXml);

        JsonObject preview = call(endpoint, session, 40, "generation.preview_statement", """
                {"namespace":"com.example.UserMapper","statementId":"findById","statementTag":"select"}
                """);
        assertEquals(original, documentText(mapperXml));
        assertTrue(preview.getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("proposedText").getAsString().contains("id=\"findById\""));

        String token = preview.get("previewToken").getAsString();
        JsonObject confirmation = call(endpoint, session, 41, "generation.confirm",
                "{\"previewToken\":\"" + token + "\"}");
        assertEquals("创建 XML statement", confirmation.get("operation").getAsString());
        assertTrue(documentText(mapperXml).contains("id=\"findById\""));
        assertToolError(endpoint, session, 42, "generation.confirm",
                "{\"previewToken\":\"" + token + "\"}", "已使用");

        FileEditor[] editor = new FileEditor[1];
        EdtTestUtil.runInEdtAndWait(() -> {
            editor[0] = FileEditorManager.getInstance(getProject()).getSelectedEditor();
            assertNotNull(editor[0]);
            UndoManager undoManager = UndoManager.getInstance(getProject());
            assertTrue(undoManager.isUndoAvailable(editor[0]));
            TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
            undoManager.undo(editor[0]);
            FileDocumentManager.getInstance().saveAllDocuments();
        });
        assertEquals(original, documentText(mapperXml));

        JsonObject driftPreview = call(endpoint, session, 43,
                "generation.preview_statement", """
                {"namespace":"com.example.UserMapper","statementId":"findAll","statementTag":"select"}
                """);
        EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.runWriteCommandAction(
                getProject(), () -> {
                    Document document = FileDocumentManager.getInstance()
                            .getDocument(mapperXml.getVirtualFile());
                    assertNotNull(document);
                    document.insertString(document.getTextLength(), "<!-- 用户并发修改 -->\n");
                    PsiDocumentManager.getInstance(getProject()).commitDocument(document);
                }));
        String drifted = documentText(mapperXml);
        String driftToken = driftPreview.get("previewToken").getAsString();
        assertToolError(endpoint, session, 44, "generation.confirm",
                "{\"previewToken\":\"" + driftToken + "\"}", "内容已变化");
        assertEquals(drifted, documentText(mapperXml));
        assertFalse(documentText(mapperXml).contains("id=\"findAll\""));
    }

    public void testToolArgumentsRejectUnknownWrongTypeAndOversizedRequest() throws Exception {
        enableMcp();
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        Map<String, String> session = session(endpoint, initialized.sessionId);

        assertToolError(endpoint, session, 50, "mapper.list",
                "{\"qualifiedName\":\"com.example.Missing\",\"unknown\":true}",
                "不支持的参数");
        assertToolError(endpoint, session, 51, "mapper.list",
                "{\"qualifiedName\":123}", "必须为字符串");
        String oversized = " ".repeat(MyBatisMcpHttpServer.MAX_REQUEST_BYTES + 1);
        assertEquals(413, request(endpoint, "POST", oversized, auth(endpoint)).status);
    }

    public void testMapperTestPreviewAndConfirmCreateOnlyInsideProject() throws Exception {
        myFixture.addFileToProject("src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    User findById(long id);
                }
                record User(long id) {}
                """);
        commitAllDocuments();
        DumbService.getInstance(getProject()).waitForSmartMode();
        enableWriteMcp("generation.confirm", "generation.preview_test");
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        Map<String, String> session = session(endpoint, initialized.sessionId);

        JsonObject preview = call(endpoint, session, 60, "generation.preview_test", """
                {"qualifiedName":"com.example.UserMapper","signature":"findById(long)","platform":"JUNIT_5"}
                """);
        JsonObject entry = preview.getAsJsonArray("entries").get(0).getAsJsonObject();
        String path = entry.get("path").getAsString();
        assertTrue(path.startsWith("src/test/java/com/example/"));
        assertFalse(path.contains(".."));
        VirtualFile root = ProjectUtil.guessProjectDir(getProject());
        assertNotNull(root);
        assertNull(root.findFileByRelativePath(path));

        call(endpoint, session, 61, "generation.confirm",
                "{\"previewToken\":\"" + preview.get("previewToken").getAsString() + "\"}");
        VirtualFile created = root.findFileByRelativePath(path);
        assertNotNull(created);
        assertTrue(com.intellij.openapi.vfs.VfsUtilCore.loadText(created)
                .contains("mapper.findById(id)"));
        assertToolError(endpoint, session, 62, "generation.preview_test", """
                {"qualifiedName":"com.example.UserMapper","signature":"findById(long)","platform":"JUNIT_5"}
                """, "目标已存在");
    }

    public void testCrudPreviewUsesLoadedMetadataAndConfirmCreatesWholeBatch()
            throws Exception {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new MyBatisDatabaseMetadataProvider() {
                    @Override
                    public String id() {
                        return "mcp-crud-test";
                    }

                    @Override
                    public List<MyBatisDatabaseSnapshot> load(
                            Project project,
                            MyBatisDatabaseRequest request,
                            ProgressIndicator indicator) {
                        return List.of(databaseSnapshot());
                    }
                },
                getTestRootDisposable());
        MyBatisJdbcDataSourceManager.getInstance(getProject()).replace(List.of(
                new MyBatisJdbcDataSourceConfig(
                        "main",
                        "Main",
                        MyBatisSqlDialect.POSTGRESQL,
                        "jdbc:postgresql://localhost:5432/example",
                        "org.postgresql.Driver",
                        List.of("/trusted/postgresql.jar"),
                        "app_user",
                        true,
                        Optional.empty(),
                        Optional.of("public"),
                        true)));
        MyBatisDatabaseMetadataResult loaded = MyBatisDatabaseMetadataService
                .getInstance(getProject())
                .load(MyBatisDatabaseRequest.all())
                .get(3, TimeUnit.SECONDS);
        assertInstanceOf(loaded, MyBatisDatabaseMetadataResult.Loaded.class);
        enableWriteMcp(
                "database.data_sources",
                "database.schema",
                "generation.confirm",
                "generation.preview_crud");
        MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint = service.endpoint().orElseThrow();
        Response initialized = request(endpoint, "POST", initializeRequest(), auth(endpoint));
        Map<String, String> session = session(endpoint, initialized.sessionId);

        JsonObject dataSources = call(
                endpoint, session, 68, "database.data_sources", "{}");
        assertEquals(1, dataSources.get("total").getAsInt());
        JsonObject dataSource = dataSources.getAsJsonArray("dataSources")
                .get(0).getAsJsonObject();
        assertEquals("main", dataSource.get("id").getAsString());
        assertFalse(dataSource.has("jdbcUrl"));
        assertFalse(dataSource.has("username"));
        assertFalse(dataSource.has("driverJarPaths"));

        JsonObject schema = call(endpoint, session, 69, "database.schema", """
                {"dataSourceId":"main","limit":1}
                """);
        assertEquals(1, schema.get("totalTables").getAsInt());
        assertFalse(schema.get("truncated").getAsBoolean());
        JsonObject table = schema.getAsJsonArray("snapshots").get(0).getAsJsonObject()
                .getAsJsonArray("tables").get(0).getAsJsonObject();
        assertEquals("users", table.get("name").getAsString());
        assertEquals(2, table.get("totalColumns").getAsInt());

        JsonObject preview = call(endpoint, session, 70, "generation.preview_crud", """
                {
                  "dataSourceId":"main",
                  "schema":"public",
                  "table":"users",
                  "basePackage":"com.example.generated",
                  "template":"STANDARD"
                }
                """);
        assertEquals(4, preview.getAsJsonArray("entries").size());
        VirtualFile root = ProjectUtil.guessProjectDir(getProject());
        assertNotNull(root);
        for (var element : preview.getAsJsonArray("entries")) {
            assertNull(root.findFileByRelativePath(
                    element.getAsJsonObject().get("path").getAsString()));
        }

        JsonObject confirmation = call(endpoint, session, 71, "generation.confirm",
                "{\"previewToken\":\"" + preview.get("previewToken").getAsString() + "\"}");
        assertEquals(4, confirmation.getAsJsonArray("paths").size());
        for (var path : confirmation.getAsJsonArray("paths")) {
            assertNotNull(root.findFileByRelativePath(path.getAsString()));
        }
    }

    private void enableMcp() {
        MyBatisAssistantSettings.getInstance().replace(enabledState());
        service.reconcile();
        assertTrue(service.isRunning());
    }

    private void enableWriteMcp(String... tools) {
        MyBatisAssistantSettings.SettingsState state = enabledState();
        state.mcpWriteToolsEnabled = true;
        state.mcpAllowedTools = java.util.List.of(tools);
        MyBatisAssistantSettings.getInstance().replace(state);
        service.reconcile();
        assertTrue(service.isRunning());
    }

    private void commitAllDocuments() {
        EdtTestUtil.runInEdtAndWait(() ->
                PsiDocumentManager.getInstance(getProject()).commitAllDocuments());
    }

    private static String documentText(PsiFile file) {
        return ReadAction.compute(() -> {
            Document document = FileDocumentManager.getInstance()
                    .getDocument(file.getVirtualFile());
            assertNotNull(document);
            return document.getText();
        });
    }

    private static void assertToolError(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint,
            Map<String, String> headers,
            int id,
            String tool,
            String arguments,
            String expectedMessage) throws IOException {
        Response response = request(endpoint, "POST", rpc(
                id,
                "tools/call",
                "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + '}'), headers);
        assertEquals(200, response.status);
        JsonObject result = json(response).getAsJsonObject("result");
        assertTrue(response.body, result.get("isError").getAsBoolean());
        assertTrue(response.body, result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString().contains(expectedMessage));
    }

    private static MyBatisAssistantSettings.SettingsState enabledState() {
        MyBatisAssistantSettings.SettingsState state = new MyBatisAssistantSettings.SettingsState();
        state.mcpEnabled = true;
        state.mcpPort = 0;
        return state;
    }

    private static JsonObject call(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint,
            Map<String, String> headers,
            int id,
            String tool,
            String arguments) throws IOException {
        Response response = request(endpoint, "POST", rpc(
                id,
                "tools/call",
                "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + '}'), headers);
        assertEquals(200, response.status);
        JsonObject result = json(response).getAsJsonObject("result");
        assertFalse(response.body, result.get("isError").getAsBoolean());
        return result.getAsJsonObject("structuredContent");
    }

    private static MyBatisDatabaseSnapshot databaseSnapshot() {
        MyBatisDatabaseColumn id = new MyBatisDatabaseColumn(
                "id",
                "BIGINT",
                java.sql.Types.BIGINT,
                false,
                true,
                false,
                true,
                Optional.of("主键"),
                1);
        MyBatisDatabaseColumn name = new MyBatisDatabaseColumn(
                "name",
                "VARCHAR",
                java.sql.Types.VARCHAR,
                false,
                false,
                false,
                false,
                Optional.of("名称"),
                2);
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.of("public"),
                "users",
                Optional.of("用户"),
                List.of(id, name));
        return new MyBatisDatabaseSnapshot(
                "main",
                "Main",
                MyBatisSqlDialect.POSTGRESQL,
                MyBatisMetadataFreshness.READY,
                1,
                List.of(table));
    }

    private static Response request(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint,
            String method,
            String body,
            Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint.url())
                .toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        headers.forEach(connection::setRequestProperty);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = input == null
                ? ""
                : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(status, connection.getHeaderField("Mcp-Session-Id"), responseBody);
    }

    private static String rawStatus(int port, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                String status = reader.readLine();
                return status == null ? "" : status;
            }
        }
    }

    private static Map<String, String> auth(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint) {
        return Map.of("Authorization", "Bearer " + endpoint.accessToken());
    }

    private static Map<String, String> headers(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint,
            String name,
            String value) {
        Map<String, String> headers = new HashMap<>(auth(endpoint));
        headers.put(name, value);
        return headers;
    }

    private static Map<String, String> session(
            MyBatisMcpProjectService.MyBatisMcpEndpoint endpoint,
            String sessionId) {
        Map<String, String> headers = new HashMap<>(auth(endpoint));
        headers.put("Mcp-Session-Id", sessionId);
        return headers;
    }

    private static String initializeRequest() {
        return rpc(1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}");
    }

    private static String rpc(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"" + method + "\",\"params\":" + params + '}';
    }

    private static JsonObject json(Response response) {
        return JsonParser.parseString(response.body).getAsJsonObject();
    }

    private record Response(int status, String sessionId, String body) {
    }

    private static final class MyBatisAssistantSettingsCodecProbe {
        private MyBatisAssistantSettingsCodecProbe() {
        }

        static String exportedSettings() {
            return io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettingsCodec.encode(
                    MyBatisAssistantSettings.getInstance().getState());
        }
    }
}
