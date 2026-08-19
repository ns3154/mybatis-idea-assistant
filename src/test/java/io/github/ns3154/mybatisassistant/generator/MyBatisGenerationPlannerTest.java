package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MyBatisGenerationPlannerTest extends BasePlatformTestCase {
    private VirtualFile generationRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        generationRoot = myFixture.getTempDirFixture().findOrCreateDir("generation-root");
    }

    public void testPlansValidatedCreatesThenStableUnchangedEntries() throws Exception {
        MyBatisGenerationBundle bundle = generate(table(
                column("id", Types.BIGINT, false, true, true, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));

        MyBatisGenerationPlan createPlan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(bundle));

        assertSize(4, createPlan.entries());
        assertTrue(createPlan.hasChanges());
        assertFalse(createPlan.hasConflicts());
        assertTrue(createPlan.entries().stream().allMatch(entry ->
                entry.status() == MyBatisGenerationPlanStatus.CREATE));
        for (MyBatisGenerationPlanEntry entry : createPlan.entries()) {
            write(entry.artifact().relativePath(), entry.proposedText().orElseThrow());
        }

        MyBatisGenerationPlan unchangedPlan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(bundle));

        assertFalse(unchangedPlan.hasChanges());
        assertTrue(describe(unchangedPlan), unchangedPlan.entries().stream().allMatch(entry ->
                entry.status() == MyBatisGenerationPlanStatus.UNCHANGED));
    }

    public void testSchemaChangesUpdateOnlyGeneratedRegionsAndKeepManualZones() throws Exception {
        MyBatisGenerationBundle first = generate(table(
                column("id", Types.BIGINT, false, true, true, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));
        for (MyBatisGeneratedArtifact artifact : first.artifacts()) {
            String text = artifact.content();
            if (artifact.kind() == MyBatisGenerationArtifactKind.ENTITY) {
                text = text.replace("\n}\n", "\n    public String manualCode() { return \"保留\"; }\n}\n");
            }
            if (artifact.kind() == MyBatisGenerationArtifactKind.XML) {
                text = text.replace("\n</mapper>\n", "\n    <!-- 手写 SQL 必须保留 -->\n</mapper>\n");
            }
            write(artifact.relativePath(), text);
        }
        MyBatisGenerationBundle changed = generate(table(
                column("id", Types.BIGINT, false, true, true, 1),
                column("display_name", Types.VARCHAR, false, false, false, 2),
                column("created_at", Types.TIMESTAMP, false, false, false, 3)));

        MyBatisGenerationPlan plan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(changed));

        assertFalse(describe(plan), plan.hasConflicts());
        assertEquals(MyBatisGenerationPlanStatus.UPDATE,
                entry(plan, MyBatisGenerationArtifactKind.ENTITY).status());
        assertEquals(MyBatisGenerationPlanStatus.UPDATE,
                entry(plan, MyBatisGenerationArtifactKind.XML).status());
        String entity = proposed(plan, MyBatisGenerationArtifactKind.ENTITY);
        assertTrue(entity.contains("private String displayName;"));
        assertTrue(entity.contains("private LocalDateTime createdAt;"));
        assertFalse(entity.contains("private String name;"));
        assertTrue(entity.contains("manualCode"));
        String xml = proposed(plan, MyBatisGenerationArtifactKind.XML);
        assertTrue(xml.contains("display_name"));
        assertTrue(xml.contains("手写 SQL 必须保留"));
    }

    public void testReportsMarkerPathAndSyntaxConflictsBeforeAnyWrite() throws Exception {
        MyBatisGenerationBundle bundle = generate(table(
                column("id", Types.BIGINT, false, true, false, 1)));
        MyBatisGeneratedArtifact entity = artifact(bundle, MyBatisGenerationArtifactKind.ENTITY);
        write(entity.relativePath(), "package com.example; public class User {}\n");

        MyBatisGenerationPlan missingMarkers = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(bundle));

        MyBatisGenerationPlanEntry entityEntry = entry(
                missingMarkers, MyBatisGenerationArtifactKind.ENTITY);
        assertEquals(MyBatisGenerationPlanStatus.CONFLICT, entityEntry.status());
        assertEquals(MyBatisSafeMergeConflictCode.FILE_WITHOUT_MARKERS,
                entityEntry.conflictCode().orElseThrow());

        MyBatisGeneratedArtifact invalid = new MyBatisGeneratedArtifact(
                MyBatisGenerationArtifactKind.ENTITY,
                "src/main/java/com/example/Broken.java",
                MyBatisGeneratedRegion.render(
                        MyBatisGeneratedRegion.Style.JAVA,
                        "broken:header",
                        "package com.example; public class {\n"),
                Set.of("broken:header"));
        MyBatisGenerationPlan invalidPlan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(new MyBatisGenerationBundle(
                        "Broken", List.of(invalid))));
        assertEquals(MyBatisSafeMergeConflictCode.INVALID_GENERATED_CONTENT,
                invalidPlan.entries().getFirst().conflictCode().orElseThrow());

        MyBatisGeneratedArtifact collisionA = new MyBatisGeneratedArtifact(
                MyBatisGenerationArtifactKind.ENTITY,
                "src/main/java/com/example/Collision.java",
                MyBatisGeneratedRegion.render(MyBatisGeneratedRegion.Style.JAVA,
                        "collision:a", "package com.example; public class Collision {}\n"),
                Set.of("collision:a"));
        MyBatisGeneratedArtifact collisionB = new MyBatisGeneratedArtifact(
                MyBatisGenerationArtifactKind.ENTITY,
                collisionA.relativePath(),
                MyBatisGeneratedRegion.render(MyBatisGeneratedRegion.Style.JAVA,
                        "collision:b", "package com.example; public class Collision { int x; }\n"),
                Set.of("collision:b"));
        MyBatisGenerationPlan collisionPlan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(
                        new MyBatisGenerationBundle("Collision", List.of(collisionA)),
                        new MyBatisGenerationBundle("Collision", List.of(collisionB))));
        assertEquals(MyBatisSafeMergeConflictCode.PATH_COLLISION,
                collisionPlan.entries().getFirst().conflictCode().orElseThrow());
    }

    public void testUnsavedDocumentIsTheMergeAndPreflightSourceOfTruth() throws Exception {
        MyBatisGenerationBundle first = generate(table(
                column("id", Types.BIGINT, false, true, false, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));
        MyBatisGeneratedArtifact firstEntity = artifact(
                first, MyBatisGenerationArtifactKind.ENTITY);
        write(firstEntity.relativePath(), firstEntity.content());
        VirtualFile file = root().findFileByRelativePath(firstEntity.relativePath());
        assertNotNull(file);
        Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteAction.runAndWait(() -> document.setText(document.getText().replace(
                "\n}\n",
                "\n    public String unsavedManual() { return \"保留\"; }\n}\n")));
        assertTrue(document.isWritable());
        MyBatisGenerationBundle changed = generate(table(
                column("id", Types.BIGINT, false, true, false, 1),
                column("email", Types.VARCHAR, true, false, false, 2)));
        MyBatisGeneratedArtifact changedEntity = artifact(
                changed, MyBatisGenerationArtifactKind.ENTITY);

        MyBatisGenerationPlan plan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(new MyBatisGenerationBundle(
                        changed.entityName(), List.of(changedEntity))));

        MyBatisGenerationPlanEntry entry = plan.entries().getFirst();
        assertEquals(MyBatisGenerationPlanStatus.UPDATE, entry.status());
        assertTrue(entry.existingText().orElseThrow().contains("unsavedManual"));
        assertTrue(entry.proposedText().orElseThrow().contains("unsavedManual"));
        assertTrue(entry.proposedText().orElseThrow().contains("private String email;"));
    }

    public void testReadOnlyDirectoryTargetAndCancellationAreTyped() throws Exception {
        MyBatisGenerationBundle bundle = generate(table(
                column("id", Types.BIGINT, false, true, false, 1)));
        MyBatisGeneratedArtifact entity = artifact(bundle, MyBatisGenerationArtifactKind.ENTITY);
        VirtualFile directory = WriteAction.computeAndWait(() ->
                createDirectory(entity.relativePath()));

        MyBatisGenerationPlan directoryPlan = MyBatisGenerationPlanner.plan(
                getProject(), root(), List.of(new MyBatisGenerationBundle(
                        bundle.entityName(), List.of(entity))));

        assertEquals(MyBatisSafeMergeConflictCode.TARGET_IS_DIRECTORY,
                directoryPlan.entries().getFirst().conflictCode().orElseThrow());
        WriteAction.runAndWait(() -> directory.delete(this));

        write(entity.relativePath(), entity.content());
        VirtualFile readOnly = root().findFileByRelativePath(entity.relativePath());
        assertNotNull(readOnly);
        WriteAction.runAndWait(() -> readOnly.setWritable(false));
        try {
            MyBatisGenerationPlan readOnlyPlan = MyBatisGenerationPlanner.plan(
                    getProject(), root(), List.of(new MyBatisGenerationBundle(
                            bundle.entityName(), List.of(entity))));
            assertEquals(MyBatisSafeMergeConflictCode.TARGET_READ_ONLY,
                    readOnlyPlan.entries().getFirst().conflictCode().orElseThrow());
        } finally {
            WriteAction.runAndWait(() -> readOnly.setWritable(true));
        }

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisGenerationPlanner.plan(
                            getProject(), root(), List.of(bundle));
                }, indicator));
    }

    private VirtualFile root() {
        return generationRoot;
    }

    private void write(String path, String text) throws Exception {
        WriteAction.runAndWait(() -> {
            int separator = path.lastIndexOf('/');
            VirtualFile parent = createDirectory(path.substring(0, separator));
            String fileName = path.substring(separator + 1);
            VirtualFile file = parent.findChild(fileName);
            if (file == null) {
                file = parent.createChildData(this, fileName);
            }
            VfsUtil.saveText(file, text);
        });
    }

    private VirtualFile createDirectory(String path) throws java.io.IOException {
        VirtualFile current = root();
        for (String segment : path.split("/")) {
            VirtualFile child = current.findChild(segment);
            current = child == null
                    ? current.createChildDirectory(this, segment)
                    : child;
        }
        return current;
    }

    private static String proposed(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return entry(plan, kind).proposedText().orElseThrow();
    }

    private static String describe(MyBatisGenerationPlan plan) {
        return plan.entries().stream()
                .map(entry -> entry.artifact().relativePath() + "=" + entry.status()
                        + entry.message().map(message -> "(" + message + ")").orElse(""))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static MyBatisGenerationPlanEntry entry(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return plan.entries().stream()
                .filter(candidate -> candidate.artifact().kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static MyBatisGeneratedArtifact artifact(
            MyBatisGenerationBundle bundle,
            MyBatisGenerationArtifactKind kind) {
        return bundle.artifacts().stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static MyBatisGenerationBundle generate(MyBatisDatabaseTable table) {
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "main",
                MyBatisSqlDialect.GENERIC,
                table,
                MyBatisGenerationConfiguration.standard("com.example")));
    }

    private static MyBatisDatabaseTable table(MyBatisDatabaseColumn... columns) {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                Optional.of("用户表"),
                List.of(columns));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean autoIncrement,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                jdbcType == Types.BIGINT ? "BIGINT"
                        : jdbcType == Types.TIMESTAMP ? "TIMESTAMP" : "VARCHAR",
                jdbcType,
                nullable,
                primaryKey,
                false,
                autoIncrement,
                Optional.of("字段 " + name),
                position);
    }
}
