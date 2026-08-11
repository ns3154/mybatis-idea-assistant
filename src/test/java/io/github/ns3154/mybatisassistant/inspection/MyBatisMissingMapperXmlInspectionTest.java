package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisMissingMapperXmlInspectionTest extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisMissingMapperXml";
    private VirtualFile resourceRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        resourceRoot = myFixture.getTempDirFixture().findOrCreateDir("resources");
        PsiTestUtil.addResourceContentToRoots(getModule(), resourceRoot, false);
        addMyBatisAnnotationStubs();
        myFixture.enableInspections(registeredExtension().instantiateTool());
    }

    public void testInspectionRegistrationAndDescription() {
        LocalInspectionEP extension = registeredExtension();
        InspectionProfileEntry tool = extension.instantiateTool();

        assertEquals("JAVA", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("Mapper 接口缺少 XML", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisMissingMapperXmlInspection.class.getName(),
                extension.implementationClass);
        assertEquals(MyBatisMissingMapperXmlInspection.class, tool.getClass());
    }

    public void testReportsOnlyConfirmedMapperThatRequiresXml() {
        configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals(HighlightSeverity.WARNING, warnings.getFirst().getSeverity());
        assertEquals(
                "已确认的 Mapper 接口缺少 Mapper XML：com.example.UserMapper",
                warnings.getFirst().getDescription());

        configureJava("""
                package com.example;
                public interface PlainService {
                    Object findById(long id);
                }
                """);
        assertEmpty(warnings());
    }

    public void testAnnotationOnlyMapperDoesNotRequireXml() {
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.Flush;
                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.SelectProvider;

                @Mapper
                public interface UserMapper {
                    @Select("select 1") Object direct();
                    @SelectProvider(type = SqlProvider.class, method = "sql")
                    Object provided();
                    @Flush Object flushStatements();
                }

                final class SqlProvider {
                    public static String sql() { return "select 1"; }
                }
                """);

        assertEmpty(warnings());
    }

    public void testExistingMapperXmlSuppressesWarning() {
        myFixture.addFileToProject("resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        assertEmpty(warnings());
    }

    public void testCreateMapperXmlQuickFixHasPreviewAndUndo() throws Throwable {
        configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface User<caret>Mapper {
                    Object findById(long id);
                }
                """);
        myFixture.doHighlighting();
        IntentionAction action = myFixture.getAvailableIntentions().stream()
                .filter(candidate -> candidate.getText().startsWith("创建 Mapper XML（"))
                .findFirst()
                .orElseThrow();

        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull(preview);
        assertTrue(preview, preview.contains("<mapper namespace=\"com.example.UserMapper\">"));
        assertTrue(preview, preview.contains("TODO: 添加 statement"));
        assertNull(resourceRoot.findFileByRelativePath("mapper/UserMapper.xml"));

        myFixture.launchAction(action);
        VirtualFile created = resourceRoot.findFileByRelativePath("mapper/UserMapper.xml");
        assertNotNull(created);
        assertNotNull(com.intellij.psi.PsiManager.getInstance(getProject()).findFile(created));
        assertTrue(com.intellij.psi.PsiManager.getInstance(getProject()).findFile(created).getText()
                .contains("namespace=\"com.example.UserMapper\""));

        FileEditor editor = FileEditorManager.getInstance(getProject()).getSelectedEditor();
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));

        assertNull(resourceRoot.findFileByRelativePath("mapper/UserMapper.xml"));
    }

    public void testEveryProductionResourceRootBecomesExplicitTarget() throws Exception {
        VirtualFile secondRoot = myFixture.getTempDirFixture().findOrCreateDir("resources-extra");
        PsiTestUtil.addResourceContentToRoots(getModule(), secondRoot, false);
        configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface User<caret>Mapper {
                    Object findById(long id);
                }
                """);
        myFixture.doHighlighting();

        List<String> actions = myFixture.getAvailableIntentions().stream()
                .map(IntentionAction::getText)
                .filter(text -> text.startsWith("创建 Mapper XML（"))
                .toList();

        assertSize(2, actions);
        assertTrue(actions.stream().anyMatch(text -> text.contains("resources/mapper")));
        assertTrue(actions.stream().anyMatch(text -> text.contains("resources-extra/mapper")));
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Throwable {
        PsiJavaFile javaFile = configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        PsiClass mapper = javaFile.getClasses()[0];

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertEmpty(
                inspect(javaFile, mapper).getResults()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return inspect(javaFile, mapper).getResults();
                    },
                    indicator);
            fail("取消后的缺失 Mapper XML 检查必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，不能转成无问题结果。
        }
    }

    private void addMyBatisAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Mapper.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Mapper {}
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Select.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Select { String[] value(); }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/SelectProvider.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface SelectProvider {
                            Class<?> type();
                            String method();
                        }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Flush.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Flush {}
                        """);
    }

    private PsiJavaFile configureJava(String source) {
        return (PsiJavaFile) myFixture.configureByText("UserMapper.java", source);
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }

    private ProblemsHolder inspect(PsiJavaFile file, PsiClass mapper) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisMissingMapperXmlInspection()
                .buildVisitor(holder, true);
        mapper.accept(visitor);
        return holder;
    }

    private LocalInspectionEP registeredExtension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList()
                .stream()
                .filter(extension -> SHORT_NAME.equals(extension.shortName))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }
}
