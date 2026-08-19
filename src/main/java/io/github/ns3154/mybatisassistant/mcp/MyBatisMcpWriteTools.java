package io.github.ns3154.mybatisassistant.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedArtifact;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanStatus;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanner;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationTemplateGroup;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisJUnitPlatform;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestGeneration;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestRequestFactory;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestSkeletonGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 受控写工具：所有写入都先返回短期预览令牌，再由独立确认调用执行。
 */
final class MyBatisMcpWriteTools {
    private static final Set<String> STATEMENT_TAGS = Set.of(
            "select", "insert", "update", "delete");

    private MyBatisMcpWriteTools() {
    }

    static @NotNull List<MyBatisMcpTool> all(
            @NotNull MyBatisMcpWritePreviewStore previewStore) {
        return List.of(
                new ConfirmTool(previewStore),
                new CrudPreviewTool(previewStore),
                new StatementPreviewTool(previewStore),
                new TestPreviewTool(previewStore));
    }

    private abstract static class WriteTool implements MyBatisMcpTool {
        final MyBatisMcpWritePreviewStore previewStore;

        WriteTool(@NotNull MyBatisMcpWritePreviewStore previewStore) {
            this.previewStore = previewStore;
        }

        @Override
        public final boolean readOnly() {
            return false;
        }

        @Override
        public final @NotNull JsonObject inputSchema() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties());
            JsonArray required = new JsonArray();
            requiredProperties().forEach(required::add);
            schema.add("required", required);
            schema.addProperty("additionalProperties", false);
            return schema;
        }

        protected abstract @NotNull JsonObject properties();

        protected abstract @NotNull Set<String> requiredProperties();
    }

    private static final class ConfirmTool extends WriteTool {
        ConfirmTool(@NotNull MyBatisMcpWritePreviewStore previewStore) {
            super(previewStore);
        }

        @Override
        public @NotNull String name() {
            return "generation.confirm";
        }

        @Override
        public @NotNull String description() {
            return MyBatisAssistantBundle.message("mcp.tool.generation.confirm.description");
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("previewToken", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.preview.token")));
            return properties;
        }

        @Override
        protected @NotNull Set<String> requiredProperties() {
            return Set.of("previewToken");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String token = requiredString(arguments, "previewToken");
            MyBatisMcpWritePreviewStore.Confirmation confirmation =
                    previewStore.confirm(token);
            JsonObject result = new JsonObject();
            result.addProperty("operation", confirmation.operation());
            JsonArray paths = new JsonArray();
            confirmation.paths().forEach(paths::add);
            result.add("paths", paths);
            result.addProperty("undo", MyBatisAssistantBundle.message(
                    "mcp.result.undo.available"));
            return result;
        }
    }

    private static final class CrudPreviewTool extends WriteTool {
        CrudPreviewTool(@NotNull MyBatisMcpWritePreviewStore previewStore) {
            super(previewStore);
        }

        @Override
        public @NotNull String name() {
            return "generation.preview_crud";
        }

        @Override
        public @NotNull String description() {
            return MyBatisAssistantBundle.message(
                    "mcp.tool.generation.preview.crud.description");
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("dataSourceId", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.data.source.id")));
            properties.add("schema", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.schema.optional")));
            properties.add("table", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.table.name")));
            properties.add("basePackage", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.base.package")));
            properties.add("template", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.template.crud")));
            return properties;
        }

        @Override
        protected @NotNull Set<String> requiredProperties() {
            return Set.of("basePackage", "dataSourceId", "table");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String dataSourceId = requiredString(arguments, "dataSourceId");
            String schema = optionalString(arguments, "schema");
            String tableName = requiredString(arguments, "table");
            String basePackage = requiredString(arguments, "basePackage");
            String templateName = optionalString(arguments, "template");
            MyBatisGenerationTemplateGroup template = templateName.isEmpty()
                    ? MyBatisGenerationTemplateGroup.STANDARD
                    : enumValue(MyBatisGenerationTemplateGroup.class, templateName, "template");
            MyBatisDatabaseSnapshot snapshot = MyBatisDatabaseMetadataService.getInstance(project)
                    .latest()
                    .orElseThrow(() -> new MyBatisMcpToolException(
                            MyBatisAssistantBundle.message(
                                    "mcp.error.database.metadata.not.loaded")))
                    .snapshots().stream()
                    .filter(candidate -> dataSourceId.equals(candidate.dataSourceId()))
                    .findFirst()
                    .orElseThrow(() -> new MyBatisMcpToolException(
                            MyBatisAssistantBundle.message(
                                    "mcp.error.data.source.not.loaded", dataSourceId)));
            List<MyBatisDatabaseTable> matching = snapshot.tables().stream()
                    .filter(table -> tableName.equals(table.name()))
                    .filter(table -> schema.isEmpty() || table.schema().map(schema::equals).orElse(false))
                    .toList();
            if (matching.size() != 1) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        matching.isEmpty()
                                ? "mcp.error.table.not.found"
                                : "mcp.error.table.ambiguous",
                        tableName));
            }
            MyBatisGenerationConfiguration standard =
                    MyBatisGenerationConfiguration.standard(basePackage);
            MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                    standard.basePackage(),
                    standard.javaSourceRoot(),
                    standard.resourceRoot(),
                    EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                    template,
                    standard.tablePrefix(),
                    standard.entitySuffix(),
                    standard.generateComments(),
                    standard.escapeSqlKeywords(),
                    Set.of(),
                    Map.of());
            MyBatisGenerationBundle bundle = MyBatisGenerationEngine.generate(
                    new MyBatisGenerationRequest(
                            dataSourceId,
                            snapshot.dialect(),
                            matching.getFirst(),
                            configuration));
            MyBatisGenerationPlan plan = read(project, () -> MyBatisGenerationPlanner.plan(
                    project, projectRoot(project), List.of(bundle)));
            return preview(MyBatisAssistantBundle.message(
                    "mcp.operation.generate.crud"), plan, previewStore);
        }
    }

    private static final class StatementPreviewTool extends WriteTool {
        StatementPreviewTool(@NotNull MyBatisMcpWritePreviewStore previewStore) {
            super(previewStore);
        }

        @Override
        public @NotNull String name() {
            return "generation.preview_statement";
        }

        @Override
        public @NotNull String description() {
            return MyBatisAssistantBundle.message(
                    "mcp.tool.generation.preview.statement.description");
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("namespace", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.mapper.namespace")));
            properties.add("statementId", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.statement.id")));
            properties.add("statementTag", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.statement.tag")));
            return properties;
        }

        @Override
        protected @NotNull Set<String> requiredProperties() {
            return Set.of("namespace", "statementId", "statementTag");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String namespace = requiredString(arguments, "namespace");
            String statementId = requiredString(arguments, "statementId");
            String statementTag = requiredString(arguments, "statementTag");
            if (!STATEMENT_TAGS.contains(statementTag)) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.statement.tag.unsupported", statementTag));
            }
            if (!statementId.matches("[A-Za-z_$][A-Za-z0-9_.$-]{0,255}")) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.statement.id.invalid"));
            }
            MyBatisGenerationPlan plan = read(project, () -> statementPlan(
                    project, namespace, statementId, statementTag));
            return preview(MyBatisAssistantBundle.message(
                    "mcp.operation.create.statement"), plan, previewStore);
        }
    }

    private static final class TestPreviewTool extends WriteTool {
        TestPreviewTool(@NotNull MyBatisMcpWritePreviewStore previewStore) {
            super(previewStore);
        }

        @Override
        public @NotNull String name() {
            return "generation.preview_test";
        }

        @Override
        public @NotNull String description() {
            return MyBatisAssistantBundle.message(
                    "mcp.tool.generation.preview.test.description");
        }

        @Override
        protected @NotNull JsonObject properties() {
            JsonObject properties = new JsonObject();
            properties.add("qualifiedName", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.mapper.qualified.name")));
            properties.add("signature", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.method.signature")));
            properties.add("platform", stringSchema(MyBatisAssistantBundle.message(
                    "mcp.schema.junit.platform")));
            return properties;
        }

        @Override
        protected @NotNull Set<String> requiredProperties() {
            return Set.of("qualifiedName", "signature");
        }

        @Override
        public @NotNull JsonObject invoke(
                @NotNull Project project,
                @NotNull JsonObject arguments) {
            String qualifiedName = requiredString(arguments, "qualifiedName");
            String signature = requiredString(arguments, "signature");
            MyBatisJUnitPlatform platform = enumValue(
                    MyBatisJUnitPlatform.class,
                    optionalString(arguments, "platform").isEmpty()
                            ? "JUNIT_5"
                            : optionalString(arguments, "platform"),
                    "platform");
            MyBatisGenerationPlan plan = read(project, () -> testPlan(
                    project, qualifiedName, signature, platform));
            return preview(MyBatisAssistantBundle.message(
                    "mcp.operation.create.mapper.test"), plan, previewStore);
        }
    }

    private static @NotNull MyBatisGenerationPlan statementPlan(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull String statementTag) {
        if (DumbService.isDumb(project)) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.index.not.ready"));
        }
        List<XmlTag> roots = MyBatisXmlSymbolLocator.findMapperRoots(project, namespace);
        if (roots.size() != 1) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    roots.isEmpty()
                            ? "mcp.error.mapper.xml.not.found"
                            : "mcp.error.mapper.xml.ambiguous"));
        }
        VirtualFile file = roots.getFirst().getContainingFile().getVirtualFile();
        if (file == null || !file.isWritable()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.xml.incomplete.or.readonly"));
        }
        var document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.xml.document.unavailable"));
        }
        String original = document.getText();
        XmlFile current = (XmlFile) PsiFileFactory.getInstance(project).createFileFromText(
                "McpStatementSource.xml", XmlFileType.INSTANCE, original);
        XmlTag root = current.getRootTag();
        if (root == null
                || PsiTreeUtil.hasErrorElements(current)
                || !MyBatisXmlModel.isMapperRoot(root)
                || !namespace.equals(MyBatisXmlModel.namespace(root))) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.xml.incomplete.or.readonly"));
        }
        for (XmlTag child : root.getSubTags()) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(child)
                    && statementId.equals(MyBatisXmlModel.statementId(child))) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.statement.exists", statementId));
            }
        }
        String closingMarkup = "</" + root.getName();
        int closingOffsetInRoot = root.getText().lastIndexOf(closingMarkup);
        if (closingOffsetInRoot < 0) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.xml.incomplete.or.readonly"));
        }
        String lineSeparator = original.contains("\r\n") ? "\r\n" : "\n";
        int offset = root.getTextRange().getStartOffset() + closingOffsetInRoot;
        String escapedId = StringUtil.escapeXmlEntities(statementId);
        String insertion = "    <" + statementTag + " id=\"" + escapedId + "\">"
                + lineSeparator
                + "        <!-- TODO: SQL -->" + lineSeparator
                + "    </" + statementTag + ">" + lineSeparator;
        String prefix = original.substring(0, offset);
        if (!prefix.endsWith(lineSeparator)) {
            insertion = lineSeparator + insertion;
        }
        String candidate = prefix + insertion + original.substring(offset);
        XmlFile parsed = (XmlFile) PsiFileFactory.getInstance(project).createFileFromText(
                "McpStatementPreview.xml", XmlFileType.INSTANCE, candidate);
        if (PsiTreeUtil.hasErrorElements(parsed)) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.xml.preview.invalid"));
        }
        return exactPlan(
                MyBatisGenerationArtifactKind.XML,
                relativePath(projectRoot(project), file),
                original,
                candidate,
                "mcp-statement");
    }

    private static @NotNull MyBatisGenerationPlan testPlan(
            @NotNull Project project,
            @NotNull String qualifiedName,
            @NotNull String signature,
            @NotNull MyBatisJUnitPlatform platform) {
        PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(
                qualifiedName,
                GlobalSearchScope.projectScope(project));
        List<PsiClass> exact = java.util.Arrays.stream(classes)
                .filter(candidate -> qualifiedName.equals(candidate.getQualifiedName()))
                .toList();
        if (exact.size() != 1) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.mapper.type.unavailable", qualifiedName));
        }
        PsiMethod method = java.util.Arrays.stream(exact.getFirst().getAllMethods())
                .filter(candidate -> signature.equals(stableSignature(candidate)))
                .findFirst()
                .orElseThrow(() -> new MyBatisMcpToolException(
                        MyBatisAssistantBundle.message(
                                "mcp.error.mapper.signature.not.found", signature)));
        MyBatisMapperTestRequestFactory.Result request =
                MyBatisMapperTestRequestFactory.create(method, platform);
        if (!(request instanceof MyBatisMapperTestRequestFactory.Result.Success success)) {
            throw new MyBatisMcpToolException(
                    ((MyBatisMapperTestRequestFactory.Result.Failure) request).message());
        }
        MyBatisMapperTestGeneration generation =
                MyBatisMapperTestSkeletonGenerator.generate(success.request());
        String packagePath = success.request().packageName().replace('.', '/');
        String relativePath = "src/test/java/"
                + (packagePath.isEmpty() ? "" : packagePath + '/')
                + generation.suggestedFileName();
        VirtualFile root = projectRoot(project);
        if (root.findFileByRelativePath(relativePath) != null) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.test.target.exists", relativePath));
        }
        MyBatisGeneratedArtifact artifact = new MyBatisGeneratedArtifact(
                MyBatisGenerationArtifactKind.SERVICE,
                relativePath,
                generation.source(),
                Set.of("mcp-test"));
        return new MyBatisGenerationPlan(List.of(new MyBatisGenerationPlanEntry(
                artifact,
                MyBatisGenerationPlanStatus.CREATE,
                Optional.empty(),
                Optional.of(generation.source()),
                Optional.empty(),
                Optional.empty())));
    }

    private static @NotNull MyBatisGenerationPlan exactPlan(
            @NotNull MyBatisGenerationArtifactKind kind,
            @NotNull String relativePath,
            @NotNull String existing,
            @NotNull String proposed,
            @NotNull String regionId) {
        MyBatisGeneratedArtifact artifact = new MyBatisGeneratedArtifact(
                kind, relativePath, proposed, Set.of(regionId));
        return new MyBatisGenerationPlan(List.of(new MyBatisGenerationPlanEntry(
                artifact,
                MyBatisGenerationPlanStatus.UPDATE,
                Optional.of(existing),
                Optional.of(proposed),
                Optional.empty(),
                Optional.empty())));
    }

    private static @NotNull JsonObject preview(
            @NotNull String operation,
            @NotNull MyBatisGenerationPlan plan,
            @NotNull MyBatisMcpWritePreviewStore store) {
        if (plan.hasConflicts()) {
            String message = plan.entries().stream()
                    .filter(entry -> entry.status() == MyBatisGenerationPlanStatus.CONFLICT)
                    .map(entry -> entry.message().orElse(MyBatisAssistantBundle.message(
                            "mcp.error.generation.conflict")))
                    .collect(java.util.stream.Collectors.joining("；"));
            throw new MyBatisMcpToolException(message);
        }
        String token = store.put(operation, plan);
        JsonObject result = new JsonObject();
        result.addProperty("previewToken", token);
        result.addProperty("expiresInSeconds", 300);
        result.addProperty("operation", operation);
        JsonArray entries = new JsonArray();
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.artifact().relativePath());
            item.addProperty("status", entry.status().name());
            item.addProperty("existingText", entry.existingText().orElse(""));
            item.addProperty("proposedText", entry.proposedText().orElse(""));
            entries.add(item);
        }
        result.add("entries", entries);
        return result;
    }

    private static @NotNull VirtualFile projectRoot(@NotNull Project project) {
        VirtualFile root = ProjectUtil.guessProjectDir(project);
        if (root == null || !root.isValid() || !root.isDirectory()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.project.root.unavailable"));
        }
        return root;
    }

    private static @NotNull String relativePath(
            @NotNull VirtualFile root,
            @NotNull VirtualFile file) {
        String relative = VfsUtilCore.getRelativePath(file, root);
        if (relative == null) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.file.outside.project"));
        }
        return relative;
    }

    private static @NotNull String stableSignature(@NotNull PsiMethod method) {
        return method.getName() + '(' + java.util.Arrays.stream(
                        method.getParameterList().getParameters())
                .map(parameter -> parameter.getType().getCanonicalText())
                .collect(java.util.stream.Collectors.joining(",")) + ')';
    }

    private static <T> @NotNull T read(
            @NotNull Project project,
            @NotNull java.util.function.Supplier<T> supplier) {
        return ApplicationManager.getApplication().runReadAction((Computable<T>) () -> {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                        "mcp.error.project.closed"));
            }
            return supplier.get();
        });
    }

    private static @NotNull JsonObject stringSchema(@NotNull String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        schema.addProperty("maxLength", 512);
        return schema;
    }

    private static @NotNull String requiredString(
            @NotNull JsonObject arguments,
            @NotNull String name) {
        String value = optionalString(arguments, name);
        if (value.isEmpty()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.argument.missing", name));
        }
        return value;
    }

    private static @NotNull String optionalString(
            @NotNull JsonObject arguments,
            @NotNull String name) {
        var value = arguments.get(name);
        if (value == null) {
            return "";
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.argument.string", name));
        }
        String normalized = value.getAsString().trim();
        if (normalized.length() > 512) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.argument.max.512", name));
        }
        return normalized;
    }

    private static <E extends Enum<E>> @NotNull E enumValue(
            @NotNull Class<E> type,
            @NotNull String value,
            @NotNull String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new MyBatisMcpToolException(MyBatisAssistantBundle.message(
                    "mcp.error.argument.value.unsupported", name, value));
        }
    }
}
