package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MyBatisSqlSchemaInspectionTest extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisSqlSchema";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(extension().instantiateTool());
    }

    public void testRegistrationDefaultsAndDescriptionAreLoadable() {
        LocalInspectionEP extension = extension();
        InspectionProfileEntry tool = extension.instantiateTool();
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());

        assertEquals("XML", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("SQL 表列与数据库元数据不一致", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisSqlSchemaInspection.class.getName(), extension.implementationClass);
        assertEquals(MyBatisSqlSchemaInspection.class, tool.getClass());
        assertNotNull(profileTool);
        assertEquals(MyBatisSqlSchemaInspection.class, profileTool.getTool().getClass());
        String description = profileTool.loadDescription();
        assertNotNull(description);
        assertTrue(description.contains("不会在编辑器线程连接数据库"));
        assertTrue(description.contains("Database Tools 不可用时保持静默"));
    }

    public void testReportsMissingTableAtExactXmlRange() throws Exception {
        warmReadyMetadata();
        configure("select * from missing_users");

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals("missing_users", warnings.getFirst().getText());
        assertEquals("数据库元数据中不存在表：missing_users",
                warnings.getFirst().getDescription());
    }

    public void testReportsMissingQualifiedColumn() throws Exception {
        warmReadyMetadata();
        configure("select u.missing from users u");

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals("missing", warnings.getFirst().getText());
        assertEquals("数据库元数据中不存在列：u.missing",
                warnings.getFirst().getDescription());
    }

    public void testReportsMissingColumnAfterDynamicFragmentAtExactXmlRange()
            throws Exception {
        warmReadyMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select u.id from users u
                        <where>
                            <if test="enabled">AND u.missing = #{value}</if>
                        </where>
                    </select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals("missing", warnings.getFirst().getText());
        assertEquals("数据库元数据中不存在列：u.missing",
                warnings.getFirst().getDescription());
    }

    public void testReportsAmbiguousUnqualifiedColumn() throws Exception {
        warmReadyMetadata();
        configure("select id from users u join orders o on u.id = o.user_id");

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals("id", warnings.getFirst().getText());
        assertEquals("数据库元数据中存在多个列候选：id",
                warnings.getFirst().getDescription());
    }

    public void testResolvedTablesAndColumnsStaySilent() throws Exception {
        warmReadyMetadata();
        configure("select u.id, u.name from public.users u where u.id = #{id}");

        assertEmpty(warnings());
    }

    public void testResultMapReportsMissingColumnAndBasicTypeMismatch()
            throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                    public void setAge(long age) { }
                }
                """);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <id property="id" column="id"/>
                        <result property="name" column="missing_column"/>
                        <result property="age" column="name"/>
                        <association property="name" column="{foreignId=id}"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select id, name from users
                    </select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings();

        assertEquals(
                List.of(
                        "Java 属性类型 long 与数据库列 name 的 JDBC 类型 VARCHAR 不匹配",
                        "数据库元数据中不存在 ResultMap 列：missing_column"),
                warnings.stream().map(HighlightInfo::getDescription).sorted().toList());
        assertEquals(
                List.of("age", "missing_column"),
                warnings.stream().map(HighlightInfo::getText).sorted().toList());
    }

    public void testResultMapWithMultipleReferencedTablesStaysSilent()
            throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User { public void setMissing(String value) { } }
                """);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="missing" column="missing_column"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">
                        select u.id, o.user_id from users u
                        join orders o on u.id = o.user_id
                    </select>
                </mapper>
                """);

        assertEmpty(warnings());
    }

    public void testLoadingDynamicAndMalformedSqlStaySilent() throws Exception {
        warmMetadata(MyBatisMetadataFreshness.LOADING);
        configure("select * from missing_users");
        assertEmpty(warnings());

        warmReadyMetadata();
        configure("select * from missing_users");
        assertEmpty(warnings());

        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User { public void setMissing(String value) { } }
                """);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserMap" type="com.example.User">
                        <result property="missing" column="missing_column"/>
                    </resultMap>
                    <select id="find" resultMap="UserMap">select id from users</select>
                </mapper>
                """);
        assertEmpty(warnings());

        configure("select * from ${table}");
        assertEmpty(warnings());

        configure("select * from users where id = #{id");
        assertEmpty(warnings());

        configure("select from");
        assertEmpty(warnings());
    }

    public void testInspectionNeverStartsMetadataRefresh() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new CountingProvider(loadStarted),
                getTestRootDisposable());
        configure("select * from users");

        assertEmpty(warnings());
        assertFalse("Inspection 不得主动连接或刷新元数据",
                loadStarted.await(500, TimeUnit.MILLISECONDS));
    }

    public void testFillUnmappedResultFieldsQuickFixHasPreview()
            throws Throwable {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">
                        select id, name from users
                    </select>
                </mapper>
                """);
        myFixture.doHighlighting();
        IntentionAction action = intentionStartingWith("补齐未映射字段");

        assertPreviewContains(action, "<id column=\"id\" property=\"id\"");
        assertPreviewContains(action, "<result column=\"name\" property=\"name\"");
        assertEquals(0, occurrences(file.getText(), "column=\"id\""));
        assertEquals(0, occurrences(file.getText(), "column=\"name\""));
    }

    public void testFillUnmappedResultFieldsExecutesInSingleUndoAndOrdersPrimaryKeyFirst()
            throws Throwable {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        IntentionAction action = intentionStartingWith("补齐未映射字段");

        myFixture.launchAction(action);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        XmlTag resultMap = file.getRootTag().findFirstSubTag("resultMap");
        assertNotNull(resultMap);
        assertEquals(1, occurrences(resultMap.getText(), "column=\"id\""));
        assertEquals(1, occurrences(resultMap.getText(), "column=\"name\""));
        assertTrue(resultMap.getText(),
                resultMap.getText().indexOf("<id ")
                        < resultMap.getText().indexOf("<result "));

        FileEditor editor = FileEditorManager.getInstance(getProject())
                .getSelectedEditor(file.getVirtualFile());
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(0, occurrences(file.getText(), "column=\"id\""));
        assertEquals(0, occurrences(file.getText(), "column=\"name\""));
    }

    public void testFillUnmappedResultFieldsStopsForReadOnlyTarget() throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        ProblemDescriptor descriptor = fillDescriptor(file);
        FillMyBatisResultMapFieldsQuickFix fix = fillQuickFix(descriptor);

        setWritable(file, false);
        try {
            fix.applyFix(getProject(), descriptor);
            assertEquals(0, occurrences(file.getText(), "column=\"id\""));
            assertEquals(0, occurrences(file.getText(), "column=\"name\""));
        } finally {
            setWritable(file, true);
        }
    }

    public void testFillUnmappedResultFieldsStopsWhenMadeReadOnlyAfterPreview()
            throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        IntentionAction action = intentionStartingWith("补齐未映射字段");
        setWritable(file, true);
        assertPreviewContains(action, "<id column=\"id\" property=\"id\"");
        ProblemDescriptor descriptor = fillDescriptor(file);
        FillMyBatisResultMapFieldsQuickFix fix = fillQuickFix(descriptor);

        setWritable(file, false);
        try {
            fix.applyFix(getProject(), descriptor);
            assertEquals(0, occurrences(file.getText(), "column=\"id\""));
            assertEquals(0, occurrences(file.getText(), "column=\"name\""));
        } finally {
            setWritable(file, true);
        }
    }

    public void testFillUnmappedResultFieldsStopsOnSourceConflict() throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">
                        select id, name from users
                    </select>
                </mapper>
                """);
        myFixture.doHighlighting();
        IntentionAction action = intentionStartingWith("补齐未映射字段");
        XmlTag resultMap = file.getRootTag().findFirstSubTag("resultMap");
        assertNotNull(resultMap);
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () -> resultMap.addSubTag(
                XmlElementFactory.getInstance(getProject()).createTagFromText(
                        "<result property=\"id\" column=\"legacy_id\"/>"),
                false));
        setWritable(file, true);

        myFixture.launchAction(action);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        assertEquals(1, occurrences(file.getText(), "column=\"legacy_id\""));
        assertEquals(0, occurrences(file.getText(), "column=\"name\""));
        assertEquals(0, occurrences(file.getText(), "column=\"id\""));
    }

    public void testFillUnmappedResultFieldsStopsWhenMetadataExpires() throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        IntentionAction action = intentionStartingWith("补齐未映射字段");
        MyBatisDatabaseMetadataService.getInstance(getProject()).invalidate();
        setWritable(file, true);

        myFixture.launchAction(action);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        assertEquals(0, occurrences(file.getText(), "column=\"id\""));
        assertEquals(0, occurrences(file.getText(), "column=\"name\""));
    }

    public void testFillUnmappedResultFieldsCancellationAfterOfflineBuildWritesNothing()
            throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public void setId(long id) { }
                    public void setName(String name) { }
                }
                """);
        XmlFile file = configureWritableXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        ProblemDescriptor descriptor = fillDescriptor(file);
        FillMyBatisResultMapFieldsQuickFix fix = fillQuickFix(descriptor);
        XmlTag resultMap = file.getRootTag().findFirstSubTag("resultMap");
        assertNotNull(resultMap);
        AtomicInteger offlineBuildChecks = new AtomicInteger();

        try {
            WriteCommandAction.runWriteCommandAction(
                    getProject(),
                    (Runnable) () -> fix.replaceWithPlan(
                            getProject(),
                            resultMap,
                            () -> {
                                if (offlineBuildChecks.incrementAndGet() == 2) {
                                    throw new ProcessCanceledException();
                                }
                            }));
            fail("离线计划构建取消必须向上传播");
        } catch (ProcessCanceledException expected) {
            // 第一个映射只加入离线副本；取消时物理 ResultMap 必须保持原样。
        }
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(2, offlineBuildChecks.get());
        assertEquals(0, occurrences(file.getText(), "column=\"id\""));
        assertEquals(0, occurrences(file.getText(), "column=\"name\""));
    }

    public void testFillUnmappedResultFieldsIsUnavailableForAmbiguousOrUnknownProperties()
            throws Exception {
        warmReadyMetadata();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User { public void setId(long id) { } }
                """);
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">
                        select u.id, o.user_id from users u
                        join orders o on u.id = o.user_id
                    </select>
                </mapper>
                """);
        myFixture.doHighlighting();
        assertFalse(hasIntentionStartingWith("补齐未映射字段"));

        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="User<caret>Map" type="com.example.User"/>
                    <select id="find" resultMap="UserMap">select id, name from users</select>
                </mapper>
                """);
        myFixture.doHighlighting();
        assertFalse(hasIntentionStartingWith("补齐未映射字段"));
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Exception {
        warmReadyMetadata();
        configure("select * from missing_users");
        XmlFile file = (XmlFile) myFixture.getFile();
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(statement);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () ->
                assertEmpty(inspect(file, statement).getResults()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return inspect(file, statement).getResults();
                    },
                    indicator);
            fail("取消后的 SQL 元数据检查必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转成空诊断。
        }
    }

    private void warmReadyMetadata() throws Exception {
        warmMetadata(MyBatisMetadataFreshness.READY);
    }

    private void warmMetadata(MyBatisMetadataFreshness freshness) throws Exception {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new FakeProvider(freshness),
                getTestRootDisposable());
        MyBatisDatabaseMetadataService.getInstance(getProject()).load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
    }

    private void configure(String sql) {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">%s</select>
                </mapper>
                """.formatted(sql));
    }

    private XmlFile configureWritableXml(String sourceWithCaret) {
        int caretOffset = sourceWithCaret.indexOf("<caret>");
        String source = sourceWithCaret.replace("<caret>", "");
        XmlFile file = (XmlFile) myFixture.addFileToProject(
                "src/main/resources/mapper/UserMapper.xml",
                source);
        if (!file.getVirtualFile().isWritable()) {
            setWritable(file, true);
        }
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        if (caretOffset >= 0) {
            myFixture.getEditor().getCaretModel().moveToOffset(caretOffset);
        }
        return file;
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting().stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }

    private ProblemsHolder inspect(XmlFile file, XmlTag tag) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisSqlSchemaInspection()
                .buildVisitor(holder, true);
        tag.accept(visitor);
        return holder;
    }

    private IntentionAction intentionStartingWith(String prefix) {
        return myFixture.getAvailableIntentions().stream()
                .filter(action -> action.getText().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private ProblemDescriptor fillDescriptor(XmlFile file) {
        XmlTag resultMap = file.getRootTag().findFirstSubTag("resultMap");
        assertNotNull(resultMap);
        return inspect(file, resultMap).getResults().stream()
                .filter(descriptor -> descriptor.getFixes() != null
                        && java.util.Arrays.stream(descriptor.getFixes())
                                .anyMatch(FillMyBatisResultMapFieldsQuickFix.class::isInstance))
                .findFirst()
                .orElseThrow();
    }

    private FillMyBatisResultMapFieldsQuickFix fillQuickFix(ProblemDescriptor descriptor) {
        return java.util.Arrays.stream(descriptor.getFixes())
                .filter(FillMyBatisResultMapFieldsQuickFix.class::isInstance)
                .map(FillMyBatisResultMapFieldsQuickFix.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private void assertPreviewContains(IntentionAction action, String expected) {
        final String[] preview = new String[1];
        com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.previewSession(
                myFixture.getEditor(),
                () -> preview[0] = myFixture.getIntentionPreviewText(action));
        assertNotNull(preview[0]);
        assertTrue(preview[0], preview[0].contains(expected));
    }

    private boolean hasIntentionStartingWith(String prefix) {
        return myFixture.getAvailableIntentions().stream()
                .anyMatch(action -> action.getText().startsWith(prefix));
    }

    private static int occurrences(String text, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private void setWritable(XmlFile file, boolean writable) {
        WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () -> {
            try {
                file.getVirtualFile().setWritable(writable);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private LocalInspectionEP extension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList().stream()
                .filter(extension -> SHORT_NAME.equals(extension.getShortName()))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }

    private static final class FakeProvider implements MyBatisDatabaseMetadataProvider {
        private final MyBatisMetadataFreshness freshness;

        private FakeProvider(MyBatisMetadataFreshness freshness) {
            this.freshness = freshness;
        }

        @Override
        public String id() {
            return "inspection-fixture-" + freshness;
        }

        @Override
        public List<MyBatisDatabaseSnapshot> load(
                Project project,
                MyBatisDatabaseRequest request,
                ProgressIndicator indicator) {
            return List.of(new MyBatisDatabaseSnapshot(
                    "main",
                    "Main",
                    MyBatisSqlDialect.POSTGRESQL,
                    freshness,
                    1,
                    List.of(
                            table("users", "id", "name"),
                            table("orders", "id", "user_id"))));
        }

        private static MyBatisDatabaseTable table(String name, String... columns) {
            List<MyBatisDatabaseColumn> metadata = java.util.stream.IntStream
                    .range(0, columns.length)
                    .mapToObj(index -> new MyBatisDatabaseColumn(
                            columns[index],
                            "id".equals(columns[index]) ? "BIGINT" : "VARCHAR",
                            "id".equals(columns[index]) ? Types.BIGINT : Types.VARCHAR,
                            true,
                            "id".equals(columns[index]),
                            false,
                            index + 1))
                    .toList();
            return new MyBatisDatabaseTable(
                    Optional.empty(),
                    Optional.of("public"),
                    name,
                    metadata);
        }
    }

    private record CountingProvider(CountDownLatch loadStarted)
            implements MyBatisDatabaseMetadataProvider {
        @Override
        public String id() {
            return "inspection-counting-fixture";
        }

        @Override
        public List<MyBatisDatabaseSnapshot> load(
                Project project,
                MyBatisDatabaseRequest request,
                ProgressIndicator indicator) {
            loadStarted.countDown();
            return List.of();
        }
    }
}
