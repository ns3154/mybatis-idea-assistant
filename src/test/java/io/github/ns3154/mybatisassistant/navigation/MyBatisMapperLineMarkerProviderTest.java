package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
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

    public void testIgnoresDuplicateStatements() {
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
}
