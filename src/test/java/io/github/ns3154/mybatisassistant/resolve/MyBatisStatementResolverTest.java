package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisStatementResolverTest extends BasePlatformTestCase {
    public void testReturnsUniqueMatchWithSmartPointer() {
        PsiFile xmlFile = addMapperXml("src/main/resources/mapper/UserMapper.xml", """
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

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.UniqueMatch);
        MyBatisStatementResolution.UniqueMatch match =
                (MyBatisStatementResolution.UniqueMatch) resolution;
        XmlTag target = ReadAction.compute(match.target()::getElement);
        assertNotNull(target);
        assertEquals("findById", target.getAttributeValue("id"));

        WriteCommandAction.runWriteCommandAction(getProject(), xmlFile::delete);
        assertNull(ReadAction.compute(match.target()::getElement));
    }

    public void testDistinguishesMissingMapperXml() {
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.NoMapperXml);
        assertEquals("com.example.UserMapper",
                ((MyBatisStatementResolution.NoMapperXml) resolution).namespace());
    }

    public void testDistinguishesMissingStatementWhenNamespaceExists() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", """
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

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.StatementMissing);
        MyBatisStatementResolution.StatementMissing missing =
                (MyBatisStatementResolution.StatementMissing) resolution;
        assertEquals("com.example.UserMapper", missing.namespace());
        assertEquals("findById", missing.statementId());
    }

    public void testEmptyMapperStillMeansStatementMissing() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.StatementMissing);
    }

    public void testPreservesMultipleMatches() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        addMapperXml("src/test/resources/mapper/UserMapperDuplicate.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 2</select>
                </mapper>
                """);
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.MultipleMatches);
        MyBatisStatementResolution.MultipleMatches matches =
                (MyBatisStatementResolution.MultipleMatches) resolution;
        assertSize(2, matches.targets());
        assertTrue(ReadAction.compute(() -> matches.targets().stream()
                .allMatch(pointer -> pointer.getElement() != null)));
    }

    public void testReturnsIndexNotReadyInDumbMode() throws Throwable {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", """
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
            MyBatisStatementResolution resolution = resolve(method);
            assertTrue(resolution instanceof MyBatisStatementResolution.IndexNotReady);
        });
    }

    public void testReturnsSourceInvalidForDeletedPsi() {
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """, "findById");
        PsiFile javaFile = method.getContainingFile();
        WriteCommandAction.runWriteCommandAction(getProject(), javaFile::delete);

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.SourceInvalid);
    }

    public void testReturnsUnsupportedSourceForConcreteClassMethod() {
        PsiMethod method = configureMapper("""
                package com.example;
                public class UserMapper {
                    public Object findById(long id) { return null; }
                }
                """, "findById");

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.UnsupportedSource);
    }

    public void testReturnsUnsupportedSourceForOverloadedMapperMethod() {
        PsiMethod method = configureMapper("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                    Object findById(String id);
                }
                """, "findById");

        MyBatisStatementResolution resolution = resolve(method);

        assertTrue(resolution instanceof MyBatisStatementResolution.UnsupportedSource);
    }

    public void testCancellationIsNotSwallowed() {
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
                        return resolve(method);
                    },
                    indicator);
            fail("取消后的解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，解析器必须向上传播。
        }
    }

    private PsiFile addMapperXml(String path, String xml) {
        return myFixture.addFileToProject(path, xml);
    }

    private PsiMethod configureMapper(String javaSource, String methodName) {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText(
                "UserMapper.java",
                javaSource);
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private MyBatisStatementResolution resolve(PsiMethod method) {
        return ReadAction.compute(() -> MyBatisStatementResolver.resolve(method));
    }
}
