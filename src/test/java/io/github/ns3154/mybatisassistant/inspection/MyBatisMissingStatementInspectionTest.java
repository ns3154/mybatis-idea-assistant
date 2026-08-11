package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.profile.codeInspection.InspectionProfileManager;

import java.util.List;

public final class MyBatisMissingStatementInspectionTest extends BasePlatformTestCase {
    private static final String INSPECTION_SHORT_NAME = "MyBatisMissingStatement";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(registeredExtension().instantiateTool());
    }

    public void testInspectionIsRegisteredByShortName() {
        LocalInspectionEP extension = registeredExtension();
        InspectionProfileEntry instantiatedTool = extension.instantiateTool();
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(INSPECTION_SHORT_NAME, getProject());

        assertEquals("JAVA", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("Mapper 方法缺少 XML statement", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisMissingStatementInspection.class.getName(),
                extension.implementationClass);
        assertEquals(MyBatisMissingStatementInspection.class,
                instantiatedTool.getClass());
        assertEquals("Mapper 方法缺少 XML statement", instantiatedTool.getDisplayName());
        assertEquals("MyBatis", instantiatedTool.getGroupDisplayName());

        assertNotNull(profileTool);
        assertEquals(MyBatisMissingStatementInspection.class,
                profileTool.getTool().getClass());
        assertEquals("Mapper 方法缺少 XML statement", profileTool.getDisplayName());
        assertEquals("MyBatis", profileTool.getGroupDisplayName());

        String description = profileTool.loadDescription();
        assertNotNull(description);
        assertTrue(description,
                description.contains("只在确认对应 namespace 的 Mapper XML 已存在"));
        assertTrue(description, description.contains("单次 Undo"));
        assertTrue(description, description.contains("Flush"));
    }

    public void testReportsOnlyMissingStatementOnMethodName() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);
        PsiJavaFile javaFile = configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        PsiMethod method = findMethod(javaFile, "findById");

        List<HighlightInfo> warnings = inspectionWarnings();

        assertSize(1, warnings);
        HighlightInfo warning = warnings.getFirst();
        assertEquals(HighlightSeverity.WARNING, warning.getSeverity());
        assertEquals("未找到 MyBatis XML statement：com.example.UserMapper.findById",
                warning.getDescription());
        assertEquals(method.getNameIdentifier().getTextRange().getStartOffset(),
                warning.getStartOffset());
        assertEquals(method.getNameIdentifier().getTextRange().getEndOffset(),
                warning.getEndOffset());
    }

    public void testDoesNotReportUniqueMatch() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportMultipleMatches() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                    <select id="findById">select 2</select>
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportWhenMapperXmlDoesNotExist() {
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportOrdinaryClassOrInterface() {
        myFixture.addFileToProject("src/main/resources/mapper/PlainService.xml", """
                <mapper namespace="com.example.PlainService">
                </mapper>
                """);
        configureJava("PlainService.java", """
                package com.example;
                public class PlainService {
                    public Object findById(long id) { return null; }
                }
                """);
        assertEmpty(inspectionWarnings());

        configureJava("UnmappedService.java", """
                package com.example;
                public interface UnmappedService {
                    Object findById(long id);
                }
                """);
        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportOverloadedMethods() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                    Object findById(String id);
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportDefaultOrStaticMethods() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    default Object localDefault() { return null; }
                    static Object localStatic() { return null; }
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportAnnotationTypeOrPrivateMethodWithBody() {
        myFixture.addFileToProject("src/main/resources/mapper/MarkerMapper.xml", """
                <mapper namespace="com.example.MarkerMapper">
                </mapper>
                """);
        configureJava("MarkerMapper.java", """
                package com.example;
                public @interface MarkerMapper {
                    String findById() default "";
                }
                """);
        assertEmpty(inspectionWarnings());

        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    private Object localHelper() { return null; }
                }
                """);
        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportAllSupportedSqlAnnotationsOrFlush() {
        addMyBatisAnnotationStubs();
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.Delete;
                import org.apache.ibatis.annotations.DeleteProvider;
                import org.apache.ibatis.annotations.Flush;
                import org.apache.ibatis.annotations.Insert;
                import org.apache.ibatis.annotations.InsertProvider;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.SelectProvider;
                import org.apache.ibatis.annotations.Update;
                import org.apache.ibatis.annotations.UpdateProvider;

                public interface UserMapper {
                    @Select("select 1") Object selectDirect();
                    @Insert("insert into sample values (1)") int insertDirect();
                    @Update("update sample set id = 1") int updateDirect();
                    @Delete("delete from sample") int deleteDirect();

                    @SelectProvider(type = SqlProvider.class, method = "sql")
                    Object selectProvided();
                    @InsertProvider(type = SqlProvider.class, method = "sql")
                    int insertProvided();
                    @UpdateProvider(type = SqlProvider.class, method = "sql")
                    int updateProvided();
                    @DeleteProvider(type = SqlProvider.class, method = "sql")
                    int deleteProvided();
                    @Flush Object flushStatements();

                    final class SqlProvider {
                        public static String sql() { return "select 1"; }
                    }
                }
                """);

        assertEmpty(inspectionWarnings());
    }

    public void testDoesNotReportInDumbMode() throws Throwable {
        addMyBatisAnnotationStubs();
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        PsiJavaFile javaFile = configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.Select;

                public interface UserMapper {
                    Object findById(long id);
                    @Select("select 1") Object findAnnotated();
                }
                """);
        PsiMethod missingMethod = findMethod(javaFile, "findById");
        PsiMethod annotatedMethod = findMethod(javaFile, "findAnnotated");

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertEmpty(inspectMethod(javaFile, missingMethod).getResults());
            assertEmpty(inspectMethod(javaFile, annotatedMethod).getResults());
        });
    }

    public void testXmlIdChangeAddsAndRemovesWarning() {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        assertEmpty(inspectionWarnings());

        changeStatementId(xmlFile, "findAll");
        assertSize(1, inspectionWarnings());

        changeStatementId(xmlFile, "findById");
        assertEmpty(inspectionWarnings());
    }

    public void testCancellationIsNotSwallowed() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        PsiJavaFile javaFile = configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        PsiMethod method = findMethod(javaFile, "findById");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return inspectMethod(javaFile, method).getResults();
                    },
                    indicator);
            fail("取消后的检查必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，Inspection 不得吞掉。
        }
    }

    public void testCreateStatementQuickFixHasPreviewAndSingleUndo() throws Throwable {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <!-- 保留已有注释 -->
                    <sql id="columns">id, name</sql>
                </mapper>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object findBy<caret>Id(long id);
                }
                """);
        myFixture.doHighlighting();

        IntentionAction action = myFixture.findSingleIntention(
                "创建 <select> statement（src/main/resources/mapper/UserMapper.xml）");
        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull(preview);
        assertTrue(preview, preview.contains("<select id=\"findById\">"));
        assertTrue(preview, preview.contains("TODO: 补充 SQL"));
        assertFalse(xmlFile.getText().contains("<select id=\"findById\">"));

        myFixture.launchAction(action);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        XmlTag root = ((XmlFile) xmlFile).getRootTag();
        assertNotNull(root);
        XmlTag added = root.findFirstSubTag("select");
        assertNotNull(added);
        assertEquals("findById", added.getAttributeValue("id"));
        assertTrue(xmlFile.getText().contains("保留已有注释"));
        assertNotNull(root.findFirstSubTag("sql"));

        FileEditor editor = FileEditorManager.getInstance(getProject())
                .getSelectedEditor(xmlFile.getVirtualFile());
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        root = ((XmlFile) xmlFile).getRootTag();
        assertNotNull(root);
        assertNull(root.findFirstSubTag("select"));
        assertNotNull(root.findFirstSubTag("sql"));
        assertTrue(xmlFile.getText().contains("保留已有注释"));
    }

    public void testQuickFixesEnumerateStatementTagsAndTargetXmlFiles() {
        myFixture.addFileToProject("src/main/resources/mapper/First.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        myFixture.addFileToProject("src/main/resources/mapper/Second.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        configureJava("""
                package com.example;
                public interface UserMapper {
                    Object find<caret>All();
                }
                """);
        myFixture.doHighlighting();

        List<String> fixNames = myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .filter(text -> text.startsWith("创建 <"))
                .sorted()
                .toList();

        assertEquals(8, fixNames.size());
        for (String fileName : List.of(
                "src/main/resources/mapper/First.xml",
                "src/main/resources/mapper/Second.xml")) {
            for (String tag : List.of("select", "insert", "update", "delete")) {
                assertContainsElements(
                        fixNames,
                        "创建 <" + tag + "> statement（" + fileName + "）");
            }
        }
    }

    private PsiFile addMapperXml(String xml) {
        return myFixture.addFileToProject(
                "src/main/resources/mapper/UserMapper.xml",
                xml);
    }

    private PsiJavaFile configureJava(String source) {
        return configureJava("UserMapper.java", source);
    }

    private PsiJavaFile configureJava(String fileName, String source) {
        return (PsiJavaFile) myFixture.configureByText(fileName, source);
    }

    private List<HighlightInfo> inspectionWarnings() {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> INSPECTION_SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }

    private ProblemsHolder inspectMethod(PsiFile file, PsiMethod method) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisMissingStatementInspection()
                .buildVisitor(holder, true);
        method.accept(visitor);
        return holder;
    }

    private LocalInspectionEP registeredExtension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList()
                .stream()
                .filter(extension -> INSPECTION_SHORT_NAME.equals(extension.shortName))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }

    private PsiMethod findMethod(PsiJavaFile javaFile, String methodName) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private void changeStatementId(PsiFile xmlFile, String newId) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            XmlTag rootTag = ((XmlFile) xmlFile).getRootTag();
            assertNotNull(rootTag);
            XmlTag statement = rootTag.findFirstSubTag("select");
            assertNotNull(statement);
            statement.setAttribute("id", newId);
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });
    }

    private void addMyBatisAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Flush.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Flush {
                        }
                        """);
        for (String annotation : List.of("Select", "Insert", "Update", "Delete")) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/" + annotation + ".java",
                    """
                            package org.apache.ibatis.annotations;
                            public @interface %s {
                                String[] value();
                            }
                            """.formatted(annotation));
        }
        for (String annotation : List.of(
                "SelectProvider",
                "InsertProvider",
                "UpdateProvider",
                "DeleteProvider")) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/" + annotation + ".java",
                    """
                            package org.apache.ibatis.annotations;
                            public @interface %s {
                                Class<?> type();
                                String method();
                            }
                            """.formatted(annotation));
        }
    }
}
