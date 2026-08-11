package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisMissingParamAnnotationInspectionTest
        extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisMissingParamAnnotation";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationStubs();
        myFixture.enableInspections(registeredExtension().instantiateTool());
    }

    public void testRegistrationUsesOptionalWeakWarningAndLoadsDescription() {
        LocalInspectionEP extension = registeredExtension();
        InspectionProfileEntry tool = extension.instantiateTool();

        assertEquals("JAVA", extension.language);
        assertFalse(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WEAK_WARNING, extension.getDefaultLevel());
        assertEquals("Mapper 多参数缺少显式 @Param", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisMissingParamAnnotationInspection.class.getName(),
                extension.implementationClass);
        assertEquals(MyBatisMissingParamAnnotationInspection.class, tool.getClass());

        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());
        assertNotNull(profileTool);
        String description = profileTool.loadDescription();
        assertNotNull(description);
        assertTrue(description, description.contains("默认关闭"));
        assertTrue(description, description.contains("一次 Undo"));
    }

    public void testReportsEachUnnamedParameterOnlyForConfirmedXmlMapper() {
        PsiJavaFile mapper = configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Param;
                @Mapper
                public interface UserMapper {
                    Object find(@Param("name") String name, long tenantId, int limit);
                }
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(2, warnings);
        assertEquals("参数未显式声明 MyBatis 名称：tenantId",
                warnings.get(0).getDescription());
        assertEquals("参数未显式声明 MyBatis 名称：limit",
                warnings.get(1).getDescription());
        assertEquals("tenantId", warningText(mapper, warnings.get(0)));
        assertEquals("limit", warningText(mapper, warnings.get(1)));
    }

    public void testSkipsLegalOrUnprovenSources() {
        configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;
                @Mapper
                public interface UserMapper {
                    Object single(long id);
                    Object named(@Param("id") long id, @Param("tenant") long tenant);
                    @Select("select 1") Object inline(long id, long tenant);
                    default Object localDefault(long id, long tenant) { return null; }
                    static Object localStatic(long id, long tenant) { return null; }
                    private Object localPrivate(long id, long tenant) { return null; }
                    Object overloaded(long id, long tenant);
                    Object overloaded(String id, long tenant);
                }
                """);
        assertEmpty(warnings());

        configureJava("""
                package com.example;
                public interface PlainService {
                    Object find(long id, long tenant);
                }
                """);
        assertEmpty(warnings());
    }

    public void testSingleParameterQuickFixHasPreviewAndSingleUndo() throws Throwable {
        PsiJavaFile mapper = configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object find(long <caret>id, long tenantId);
                }
                """);
        myFixture.doHighlighting();
        IntentionAction action = intention("添加 @Param(\"id\")");

        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull(preview);
        assertTrue(preview, preview.contains("@Param(\"id\")"));
        assertFalse(mapper.getText().contains("@Param"));

        myFixture.launchAction(action);
        assertTrue(mapper.getText(), mapper.getText().contains("@Param(\"id\") long id"));
        assertFalse(mapper.getText().contains("@Param(\"tenantId\")"));

        undoSelectedEditor();
        assertFalse(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("@Param"));
    }

    public void testAllParametersQuickFixIsOneUndoCommand() throws Throwable {
        PsiJavaFile mapper = configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object find(long <caret>id, long tenantId);
                }
                """);
        myFixture.doHighlighting();
        IntentionAction action = intention("为方法全部参数添加 @Param");

        String preview = myFixture.getIntentionPreviewText(action);
        assertNotNull(preview);
        assertTrue(preview, preview.contains("@Param(\"id\")"));
        assertTrue(preview, preview.contains("@Param(\"tenantId\")"));

        myFixture.launchAction(action);
        assertTrue(mapper.getText(), mapper.getText().contains("@Param(\"id\") long id"));
        assertTrue(mapper.getText(), mapper.getText()
                .contains("@Param(\"tenantId\") long tenantId"));

        undoSelectedEditor();
        assertFalse(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("@Param"));
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Throwable {
        PsiJavaFile mapper = configureJava("""
                package com.example;
                import org.apache.ibatis.annotations.Mapper;
                @Mapper
                public interface UserMapper {
                    Object find(long id, long tenantId);
                }
                """);
        PsiMethod method = mapper.getClasses()[0].getMethods()[0];

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertEmpty(
                inspect(mapper, method).getResults()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return inspect(mapper, method).getResults();
                    },
                    indicator);
            fail("取消后的 @Param 检查必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转成空诊断。
        }
    }

    private void addAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Mapper.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Mapper {}
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Select.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Select { String[] value(); }
                        """);
    }

    private PsiJavaFile configureJava(String source) {
        return (PsiJavaFile) myFixture.configureByText("UserMapper.java", source);
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING).stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }

    private String warningText(PsiJavaFile file, HighlightInfo warning) {
        return file.getText().substring(warning.getStartOffset(), warning.getEndOffset());
    }

    private IntentionAction intention(String text) {
        return myFixture.getAvailableIntentions().stream()
                .filter(candidate -> text.equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void undoSelectedEditor() throws Throwable {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        FileEditor editor = FileEditorManager.getInstance(getProject())
                .getSelectedEditor(myFixture.getFile().getVirtualFile());
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    private ProblemsHolder inspect(PsiJavaFile file, PsiMethod method) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisMissingParamAnnotationInspection()
                .buildVisitor(holder, true);
        method.accept(visitor);
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
