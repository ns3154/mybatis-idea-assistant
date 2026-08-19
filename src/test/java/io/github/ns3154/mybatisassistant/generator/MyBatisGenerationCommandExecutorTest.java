package io.github.ns3154.mybatisassistant.generator;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.io.IOException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

public final class MyBatisGenerationCommandExecutorTest extends BasePlatformTestCase {
    private VirtualFile generationRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        generationRoot = myFixture.getTempDirFixture().findOrCreateDir("generation-root");
    }

    public void testCreatesAllSelectedFilesInOneNamedCommand() {
        MyBatisGenerationPlan plan = plan(generate(
                column("id", Types.BIGINT, false, true, true, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));

        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, plan);

        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            VirtualFile file = generationRoot.findFileByRelativePath(
                    entry.artifact().relativePath());
            assertNotNull(entry.artifact().relativePath(), file);
            assertEquals(entry.proposedText().orElseThrow(), load(file));
        }
    }

    public void testCreatedBatchCanBeUndoneAndRedoneOnce() {
        MyBatisGenerationPlan plan = plan(generate(
                column("id", Types.BIGINT, false, true, true, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));
        myFixture.configureByText(
                JavaFileType.INSTANCE,
                "class GenerationCreateCommandContext {}\n");

        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, plan);

        FileEditor selectedEditor = FileEditorManager.getInstance(getProject()).getSelectedEditor();
        assertNotNull(selectedEditor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(selectedEditor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        undoManager.undo(selectedEditor);
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            assertNull("一次撤销必须删除本批新建文件：" + entry.artifact().relativePath(),
                    generationRoot.findFileByRelativePath(entry.artifact().relativePath()));
        }

        assertTrue(undoManager.isRedoAvailable(selectedEditor));
        undoManager.redo(selectedEditor);
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            VirtualFile file = generationRoot.findFileByRelativePath(
                    entry.artifact().relativePath());
            assertNotNull(entry.artifact().relativePath(), file);
            assertEquals(entry.proposedText().orElseThrow(), load(file));
        }
    }

    public void testUpdatesGeneratedRegionsAndPreservesManualCodeWithOneUndoRedo()
            throws Exception {
        MyBatisGenerationBundle first = generate(
                column("id", Types.BIGINT, false, true, true, 1),
                column("name", Types.VARCHAR, true, false, false, 2));
        MyBatisGenerationPlan create = plan(first);
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, create);
        VirtualFile entity = file(create, MyBatisGenerationArtifactKind.ENTITY);
        replaceText(entity, load(entity).replace("\n}\n",
                "\n    public String manualCode() { return \"保留\"; }\n}\n"));
        MyBatisGenerationPlan update = plan(generate(
                column("id", Types.BIGINT, false, true, true, 1),
                column("display_name", Types.VARCHAR, false, false, false, 2)));
        myFixture.configureByText(
                JavaFileType.INSTANCE,
                "class GenerationCommandContext {}\n");

        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, update);

        String updated = load(entity);
        assertTrue(updated.contains("private String displayName;"));
        assertFalse(updated.contains("private String name;"));
        assertTrue(updated.contains("manualCode"));

        VirtualFile xml = file(update, MyBatisGenerationArtifactKind.XML);
        FileEditor selectedEditor = FileEditorManager.getInstance(getProject()).getSelectedEditor();
        assertNotNull(selectedEditor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue("整批生成必须形成一个可撤销命令",
                undoManager.isUndoAvailable(selectedEditor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        undoManager.undo(selectedEditor);
        FileDocumentManager.getInstance().saveAllDocuments();
        assertTrue(load(entity).contains("private String name;"));
        assertFalse(load(entity).contains("private String displayName;"));
        assertTrue(load(entity).contains("manualCode"));
        assertTrue(load(xml).contains("column=\"name\""));
        assertFalse(load(xml).contains("display_name"));

        assertTrue("整批撤销后必须能一次重做", undoManager.isRedoAvailable(selectedEditor));
        undoManager.redo(selectedEditor);
        FileDocumentManager.getInstance().saveAllDocuments();
        assertTrue(load(entity).contains("private String displayName;"));
        assertTrue(load(xml).contains("display_name"));
    }

    public void testConsecutiveGenerationBatchesKeepIndependentUndoBoundaries() {
        MyBatisGenerationPlan create = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, create);
        MyBatisGenerationPlan firstUpdate = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("email", Types.VARCHAR, true, false, false, 2)));
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, firstUpdate);
        MyBatisGenerationPlan secondUpdate = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("phone", Types.VARCHAR, true, false, false, 2)));
        myFixture.configureByText(
                JavaFileType.INSTANCE,
                "class GenerationIndependentCommandContext {}\n");
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, secondUpdate);

        VirtualFile entity = file(secondUpdate, MyBatisGenerationArtifactKind.ENTITY);
        FileEditor selectedEditor = FileEditorManager.getInstance(getProject()).getSelectedEditor();
        assertNotNull(selectedEditor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        undoManager.undo(selectedEditor);
        FileDocumentManager.getInstance().saveAllDocuments();

        String afterOneUndo = load(entity);
        assertTrue(afterOneUndo.contains("private String email;"));
        assertFalse(afterOneUndo.contains("private String phone;"));
        assertNotNull("一次撤销不得连带撤销上一批生成", entity);
    }

    public void testPreflightConflictStopsEveryFileBeforeExecution() throws Exception {
        MyBatisGenerationPlan plan = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1)));
        MyBatisGenerationPlanEntry first = plan.entries().getFirst();
        write(first.artifact().relativePath(), "用户刚刚创建的文件\n");

        IllegalStateException conflict = expectConflict(() ->
                MyBatisGenerationCommandExecutor.execute(
                        getProject(), generationRoot, plan));

        assertTrue(conflict.getMessage().contains("预览后目标已被创建"));
        assertEquals("用户刚刚创建的文件\n",
                load(generationRoot.findFileByRelativePath(first.artifact().relativePath())));
        for (MyBatisGenerationPlanEntry entry : plan.entries().subList(1, plan.entries().size())) {
            assertNull("预检失败后不得产生部分文件：" + entry.artifact().relativePath(),
                    generationRoot.findFileByRelativePath(entry.artifact().relativePath()));
        }
    }

    public void testChangedAfterPreviewStopsUpdateWithoutPartialWrites() throws Exception {
        MyBatisGenerationBundle first = generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("name", Types.VARCHAR, true, false, false, 2));
        MyBatisGenerationPlan create = plan(first);
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, create);
        MyBatisGenerationPlan update = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("email", Types.VARCHAR, true, false, false, 2)));
        VirtualFile entity = file(update, MyBatisGenerationArtifactKind.ENTITY);
        String changedAfterPreview = load(entity) + "// 外部并发修改\n";
        replaceText(entity, changedAfterPreview);

        IllegalStateException conflict = expectConflict(() ->
                MyBatisGenerationCommandExecutor.execute(
                        getProject(), generationRoot, update));

        assertTrue(conflict.getMessage().contains("预览后文件内容已变化"));
        assertEquals(changedAfterPreview, load(entity));
        VirtualFile xml = file(update, MyBatisGenerationArtifactKind.XML);
        assertFalse(load(xml).contains("email"));
    }

    public void testWriteFailureRollsBackCreatedFilesAndDirectories() {
        MyBatisGenerationPlan plan = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1)));

        IllegalStateException failure = expectConflict(() ->
                MyBatisGenerationCommandExecutor.execute(
                        getProject(),
                        generationRoot,
                        plan,
                        (path, completedWrites) -> {
                            if (completedWrites == 1) {
                                throw new IOException("模拟磁盘写入失败");
                            }
                        }));

        assertTrue(failure.getMessage().contains("已自动回滚"));
        for (MyBatisGenerationPlanEntry entry : plan.entries()) {
            assertNull("失败回滚后不得残留文件：" + entry.artifact().relativePath(),
                    generationRoot.findFileByRelativePath(entry.artifact().relativePath()));
        }
        assertNull("失败回滚后不得残留本批空目录", generationRoot.findChild("src"));
    }

    public void testWriteFailureRestoresEveryUpdatedDocument() {
        MyBatisGenerationPlan create = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("name", Types.VARCHAR, true, false, false, 2)));
        MyBatisGenerationCommandExecutor.execute(getProject(), generationRoot, create);
        MyBatisGenerationPlan update = plan(generate(
                column("id", Types.BIGINT, false, true, false, 1),
                column("email", Types.VARCHAR, true, false, false, 2)));

        IllegalStateException failure = expectConflict(() ->
                MyBatisGenerationCommandExecutor.execute(
                        getProject(),
                        generationRoot,
                        update,
                        (path, completedWrites) -> {
                            if (completedWrites == 2) {
                                throw new IOException("模拟更新中途失败");
                            }
                        }));

        assertTrue(failure.getMessage().contains("已自动回滚"));
        for (MyBatisGenerationPlanEntry entry : update.entries()) {
            VirtualFile file = generationRoot.findFileByRelativePath(
                    entry.artifact().relativePath());
            assertNotNull(entry.artifact().relativePath(), file);
            assertEquals(entry.existingText().orElseThrow(), currentText(file));
        }
    }

    private MyBatisGenerationPlan plan(MyBatisGenerationBundle bundle) {
        return MyBatisGenerationPlanner.plan(
                getProject(), generationRoot, List.of(bundle));
    }

    private static MyBatisGenerationBundle generate(MyBatisDatabaseColumn... columns) {
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                Optional.of("用户表"),
                List.of(columns));
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "main",
                MyBatisSqlDialect.GENERIC,
                table,
                MyBatisGenerationConfiguration.standard("com.example")));
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
                jdbcType == Types.BIGINT ? "BIGINT" : "VARCHAR",
                jdbcType,
                nullable,
                primaryKey,
                false,
                autoIncrement,
                Optional.empty(),
                position);
    }

    private VirtualFile file(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        String path = plan.entries().stream()
                .filter(entry -> entry.artifact().kind() == kind)
                .findFirst()
                .orElseThrow()
                .artifact()
                .relativePath();
        VirtualFile file = generationRoot.findFileByRelativePath(path);
        assertNotNull(path, file);
        return file;
    }

    private void write(String path, String text) throws Exception {
        WriteAction.runAndWait(() -> {
            int separator = path.lastIndexOf('/');
            VirtualFile parent = directory(path.substring(0, separator));
            VirtualFile file = parent.createChildData(
                    this, path.substring(separator + 1));
            VfsUtil.saveText(file, text);
        });
    }

    private VirtualFile directory(String path) throws IOException {
        VirtualFile current = generationRoot;
        for (String segment : path.split("/")) {
            VirtualFile child = current.findChild(segment);
            current = child == null
                    ? current.createChildDirectory(this, segment)
                    : child;
        }
        return current;
    }

    private static String load(VirtualFile file) {
        try {
            return VfsUtil.loadText(file);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String currentText(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        return document == null ? load(file) : document.getText();
    }

    private static void replaceText(VirtualFile file, String text) {
        FileDocumentManager manager = FileDocumentManager.getInstance();
        Document document = manager.getDocument(file);
        if (document == null) {
            throw new AssertionError("无法取得文档：" + file.getPath());
        }
        WriteAction.runAndWait(() -> document.setText(text));
        manager.saveDocument(document);
    }

    private static IllegalStateException expectConflict(Runnable action) {
        try {
            action.run();
            fail("写入冲突或失败必须停止生成");
            throw new AssertionError();
        } catch (IllegalStateException expected) {
            return expected;
        }
    }
}
