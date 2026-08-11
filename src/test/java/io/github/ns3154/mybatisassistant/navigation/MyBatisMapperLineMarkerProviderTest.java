package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisMapperLineMarkerProviderTest extends BasePlatformTestCase {
    public void testFindsUniqueMatchingStatement() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertSize(1, targets);
        assertEquals("findById", targets.getFirst().getAttributeValue("id"));
    }

    public void testIgnoresNamespaceMismatch() {
        addMapperXml("""
                <mapper namespace="com.example.OtherMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertEmpty(targets);
    }

    public void testIgnoresMissingStatement() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 1</select>
                </mapper>
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertEmpty(targets);
    }

    public void testPreservesDuplicateStatementsAsNavigationCandidates() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                    <select id="findById">select 2</select>
                </mapper>
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertSize(2, targets);

        List<RelatedItemLineMarkerInfo<?>> markers = new ArrayList<>();
        new MyBatisMapperLineMarkerProvider().collectNavigationMarkers(
                method.getNameIdentifier(), markers);
        assertSize(1, markers);
    }

    public void testIgnoresOverloadedMethods() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                    Object findById(String id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertEmpty(targets);
    }

    public void testParentInterfaceMethodNavigatesToChildMapperStatement() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod parentMethod = configureMapper("""
                package com.example;
                public interface UserMapper extends BaseMapper {
                }
                interface BaseMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(parentMethod));

        assertSize(1, targets);
        assertEquals("com.example.UserMapper",
                targets.getFirst().getParentTag().getAttributeValue("namespace"));
        List<RelatedItemLineMarkerInfo<?>> markers = new ArrayList<>();
        new MyBatisMapperLineMarkerProvider().collectNavigationMarkers(
                parentMethod.getNameIdentifier(),
                markers);
        assertSize(1, markers);
    }

    public void testParentInterfaceMethodPreservesMultipleChildMapperTargets() {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        myFixture.addFileToProject("src/main/resources/mapper/AdminMapper.xml", """
                <mapper namespace="com.example.AdminMapper">
                    <select id="findById">select 2</select>
                </mapper>
                """);
        PsiMethod parentMethod = configureMapper("""
                package com.example;
                public interface UserMapper extends BaseMapper {
                }
                interface AdminMapper extends BaseMapper {
                }
                interface BaseMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(parentMethod));

        assertSize(2, targets);
        assertEquals(
                java.util.Set.of("com.example.AdminMapper", "com.example.UserMapper"),
                targets.stream()
                        .map(XmlTag::getParentTag)
                        .map(tag -> tag.getAttributeValue("namespace"))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    public void testParentInterfaceNavigationSkipsChildWithSameNameOverload() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod parentMethod = configureMapper("""
                package com.example;
                public interface UserMapper extends BaseMapper {
                    Object findById(String id);
                }
                interface BaseMapper {
                    Object findById(long id);
                }
                """, "findById");
        PsiMethod actualParent = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiMethod.class)
                .stream()
                .filter(method -> method.getContainingClass() != null)
                .filter(method -> "BaseMapper".equals(method.getContainingClass().getName()))
                .findFirst()
                .orElseThrow();

        assertEmpty(ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(actualParent)));
        assertNotSame(parentMethod, actualParent);
    }

    public void testSupportsAllFirstBatchStatementTags() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findOne">select 1</select>
                    <insert id="insertOne">insert into sample values (1)</insert>
                    <update id="updateOne">update sample set id = 1</update>
                    <delete id="deleteOne">delete from sample</delete>
                </mapper>
                """);

        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText("UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    Object findOne();
                    int insertOne();
                    int updateOne();
                    int deleteOne();
                }
                """);

        for (String methodName : List.of("findOne", "insertOne", "updateOne", "deleteOne")) {
            PsiMethod method = findMethod(javaFile, methodName);
            List<XmlTag> targets = ReadAction.compute(
                    () -> MyBatisMapperLineMarkerProvider.findTargets(method));
            assertSize(1, targets);
            assertEquals(methodName, targets.getFirst().getAttributeValue("id"));
        }
    }

    public void testMalformedXmlDoesNotEnterIndex() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1
                """);

        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        List<XmlTag> targets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));

        assertEmpty(targets);
    }

    public void testRegisteredProviderProducesGutter() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        myFixture.doHighlighting();
        List<GutterMark> gutters = myFixture.findAllGutters();

        assertTrue(gutters.stream().anyMatch(gutter ->
                "跳转到 MyBatis XML statement".equals(gutter.getTooltipText())));
    }

    public void testRegisteredGutterNavigatesToStatement() throws Throwable {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        myFixture.doHighlighting();
        GutterMark gutter = myFixture.findAllGutters().stream()
                .filter(candidate -> "跳转到 MyBatis XML statement".equals(candidate.getTooltipText()))
                .findFirst()
                .orElseThrow();
        assertTrue(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?>);

        LineMarkerInfo<?> markerInfo =
                ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) gutter).getLineMarkerInfo();
        navigate(markerInfo);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        XmlTag statement = ((com.intellij.psi.xml.XmlFile) xmlFile)
                .getRootTag()
                .findFirstSubTag("select");
        assertNotNull(statement);
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(getProject());
        assertContainsElements(List.of(fileEditorManager.getSelectedFiles()), xmlFile.getVirtualFile());
        assertNotNull(fileEditorManager.getSelectedTextEditor());
        assertEquals(statement.getTextOffset(),
                fileEditorManager.getSelectedTextEditor().getCaretModel().getOffset());
        assertEquals(method.getName(), statement.getAttributeValue("id"));
    }

    public void testReturnsEmptyInDumbMode() throws Throwable {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            List<XmlTag> targets = ReadAction.compute(
                    () -> MyBatisMapperLineMarkerProvider.findTargets(method));
            assertEmpty(targets);
        });
    }

    public void testXmlIdChangeInvalidatesOldLookup() {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        assertSize(1, ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            XmlTag statement = ((com.intellij.psi.xml.XmlFile) xmlFile)
                    .getRootTag()
                    .findFirstSubTag("select");
            assertNotNull(statement);
            statement.setAttribute("id", "findAll");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        assertEmpty(ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));
    }

    public void testUsesFullyQualifiedMapperName() {
        addMapperXml("""
                <mapper namespace="com.first.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiJavaFile firstMapper = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/first/UserMapper.java",
                """
                        package com.first;
                        public interface UserMapper {
                            Object findById(long id);
                        }
                        """);
        PsiJavaFile secondMapper = (PsiJavaFile) myFixture.configureByText(
                "UserMapper.java",
                """
                        package com.second;
                        public interface UserMapper {
                            Object findById(long id);
                        }
                        """);

        assertSize(1, ReadAction.compute(() ->
                MyBatisMapperLineMarkerProvider.findTargets(findMethod(firstMapper, "findById"))));
        assertEmpty(ReadAction.compute(() ->
                MyBatisMapperLineMarkerProvider.findTargets(findMethod(secondMapper, "findById"))));
    }

    public void testXmlDeletionInvalidatesLookup() {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        assertSize(1, ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));
        WriteCommandAction.runWriteCommandAction(getProject(), xmlFile::delete);

        assertEmpty(ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));
    }

    public void testXmlRenameInvalidatesAndRestoresLookup() throws Exception {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");
        VirtualFile virtualFile = xmlFile.getVirtualFile();

        assertSize(1, ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));

        rename(virtualFile, "UserMapper.disabled");
        assertEmpty(ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));

        rename(virtualFile, "RenamedUserMapper.xml");
        List<XmlTag> restoredTargets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));
        assertSize(1, restoredTargets);
        assertEquals("RenamedUserMapper.xml",
                restoredTargets.getFirst().getContainingFile().getVirtualFile().getName());
    }

    public void testXmlMoveRefreshesIndexedPath() throws Exception {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");
        VirtualFile virtualFile = xmlFile.getVirtualFile();
        VirtualFile targetDirectory = myFixture.getTempDirFixture()
                .findOrCreateDir("src/main/resources/relocated");

        assertSize(1, ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method)));

        WriteAction.runAndWait(() -> virtualFile.move(this, targetDirectory));

        List<XmlTag> movedTargets = ReadAction.compute(
                () -> MyBatisMapperLineMarkerProvider.findTargets(method));
        assertSize(1, movedTargets);
        assertEquals(virtualFile,
                movedTargets.getFirst().getContainingFile().getVirtualFile());
        assertTrue(virtualFile.getPath().endsWith(
                "/src/main/resources/relocated/UserMapper.xml"));
    }

    public void testLookupHonorsCancellation() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return ReadAction.compute(
                                () -> MyBatisMapperLineMarkerProvider.findTargets(method));
                    },
                    indicator);
            fail("取消后的查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，不应在生产代码中吞掉。
        }
    }

    private PsiFile addMapperXml(String xml) {
        return myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", xml);
    }

    private PsiMethod configureMapper(String javaSource, String methodName) {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText("UserMapper.java", javaSource);
        return findMethod(javaFile, methodName);
    }

    private PsiMethod findMethod(PsiJavaFile javaFile, String methodName) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private void rename(VirtualFile virtualFile, String newName) throws Exception {
        WriteAction.runAndWait(() -> virtualFile.rename(this, newName));
    }

    private static <T extends PsiElement> void navigate(LineMarkerInfo<T> markerInfo)
            throws Throwable {
        T source = markerInfo.getElement();
        assertNotNull(source);
        GutterIconNavigationHandler<T> navigationHandler = markerInfo.getNavigationHandler();
        assertNotNull(navigationHandler);
        EdtTestUtil.runInEdtAndWait(() -> navigationHandler.navigate(null, source));
    }
}
