package io.github.ns3154.mybatisassistant.sqltool.testgen;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisMapperTestSkeletonGeneratorTest extends BasePlatformTestCase {
    public void testGeneratesCompilableJUnit5SkeletonWithSafeDefaults() {
        addJunit5Stubs();
        PsiJavaFile mapper = mapperFile();
        PsiMethod method = mapper.getClasses()[0].findMethodsByName("findByFilter", false)[0];
        MyBatisMapperTestRequest request = success(
                MyBatisMapperTestRequestFactory.create(method, MyBatisJUnitPlatform.JUNIT_5));

        MyBatisMapperTestGeneration generation =
                MyBatisMapperTestSkeletonGenerator.generate(request);

        assertTrue(generation.suggestedFileName().startsWith(
                "UserMapperFindByFilter_"));
        assertTrue(generation.source().contains("import org.junit.jupiter.api.Test;"));
        assertTrue(generation.source().contains("var id = 0L;"));
        assertTrue(generation.source().contains("var name = \"\";"));
        assertTrue(generation.source().contains("var roles = java.util.List.of();"));
        assertTrue(generation.source().contains("var date = java.time.LocalDate.now();"));
        assertTrue(generation.source().contains("com.example.Filter filter = null;"));
        assertTrue(generation.source().contains(
                "var actual = mapper.findByFilter(id, name, roles, date, filter);"));
        assertTrue(generation.source().contains("assertNotNull(actual);"));
        PsiJavaFile generated = (PsiJavaFile) myFixture.configureByText(
                generation.suggestedFileName(), generation.source());
        assertNull(PsiTreeUtil.findChildOfType(generated, PsiErrorElement.class));
    }

    public void testGeneratesJUnit4VoidSkeletonWithoutExecutingAnything() {
        PsiMethod method = mapperFile().getClasses()[0]
                .findMethodsByName("deleteById", false)[0];
        MyBatisMapperTestRequest request = success(
                MyBatisMapperTestRequestFactory.create(method, MyBatisJUnitPlatform.JUNIT_4));

        MyBatisMapperTestGeneration generation =
                MyBatisMapperTestSkeletonGenerator.generate(request);

        assertTrue(generation.source().contains("import org.junit.Before;"));
        assertTrue(generation.source().contains("public class UserMapperDeleteById_"));
        assertTrue(generation.source().contains("public void setUp()"));
        assertTrue(generation.source().contains("public void testDeleteById()"));
        assertTrue(generation.source().contains("mapper.deleteById(id);"));
        assertTrue(generation.source().contains("TODO 断言数据库状态"));
        assertFalse(generation.source().contains("var actual"));
    }

    public void testNoParameterMethodUsesStableReadableFileName() {
        PsiMethod method = mapperFile().getClasses()[0]
                .findMethodsByName("countAll", false)[0];
        MyBatisMapperTestRequest request = success(
                MyBatisMapperTestRequestFactory.create(method, MyBatisJUnitPlatform.JUNIT_5));

        MyBatisMapperTestGeneration generation =
                MyBatisMapperTestSkeletonGenerator.generate(request);

        assertEquals("UserMapperCountAllTest.java", generation.suggestedFileName());
        assertTrue(generation.source().contains("mapper.countAll();"));
    }

    public void testRejectsClassDefaultStaticBodyAndAnonymousSources() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("Sources.java", """
                package com.example;
                class Plain { int find() { return 1; } }
                interface Mapper {
                    default int defaultMethod() { return 1; }
                    static int staticMethod() { return 1; }
                    private int privateMethod() { return 1; }
                }
                """);

        assertFailure(file.getClasses()[0].findMethodsByName("find", false)[0]);
        assertFailure(file.getClasses()[1].findMethodsByName("defaultMethod", false)[0]);
        assertFailure(file.getClasses()[1].findMethodsByName("staticMethod", false)[0]);
        assertFailure(file.getClasses()[1].findMethodsByName("privateMethod", false)[0]);
    }

    public void testSupportsPrimitiveArrayCollectionAndNullDefaults() {
        assertEquals("false", expression("boolean"));
        assertEquals("'\\0'", expression("char"));
        assertEquals("0D", expression("double"));
        assertEquals("new int[0]", expression("int[]"));
        assertEquals("java.util.Map.of()",
                expression("java.util.Map<java.lang.String, java.lang.Long>"));
        assertEquals("java.util.Optional.empty()",
                expression("java.util.Optional<com.example.User>"));
        assertEquals("null", expression("com.example.User"));
    }

    public void testLargeParameterGenerationPropagatesCancellation() {
        List<MyBatisMapperTestParameter> parameters = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            parameters.add(new MyBatisMapperTestParameter("p" + index, "java.lang.String"));
        }
        MyBatisMapperTestRequest request = new MyBatisMapperTestRequest(
                "com.example",
                "com.example.LargeMapper",
                "LargeMapper",
                "find",
                "find(many)",
                false,
                parameters,
                MyBatisJUnitPlatform.JUNIT_5);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisMapperTestSkeletonGenerator.generate(request);
                }, indicator));
    }

    private PsiJavaFile mapperFile() {
        myFixture.addFileToProject(
                "com/example/User.java",
                "package com.example; public final class User {}");
        myFixture.addFileToProject(
                "com/example/Filter.java",
                "package com.example; public final class Filter {}");
        return (PsiJavaFile) myFixture.configureByText("UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    User findByFilter(long id, String name, java.util.List<String> roles,
                                      java.time.LocalDate date, Filter filter);
                    void deleteById(long id);
                    long countAll();
                }
                """);
    }

    private void addJunit5Stubs() {
        myFixture.addFileToProject("org/junit/jupiter/api/Test.java", """
                package org.junit.jupiter.api;
                public @interface Test {}
                """);
        myFixture.addFileToProject("org/junit/jupiter/api/BeforeEach.java", """
                package org.junit.jupiter.api;
                public @interface BeforeEach {}
                """);
        myFixture.addFileToProject("org/junit/jupiter/api/Assertions.java", """
                package org.junit.jupiter.api;
                public final class Assertions {
                    public static void assertNotNull(Object value) {}
                }
                """);
    }

    private static MyBatisMapperTestRequest success(
            MyBatisMapperTestRequestFactory.Result result) {
        assertTrue(result instanceof MyBatisMapperTestRequestFactory.Result.Success);
        return ((MyBatisMapperTestRequestFactory.Result.Success) result).request();
    }

    private static void assertFailure(PsiMethod method) {
        assertTrue(MyBatisMapperTestRequestFactory.create(
                method, MyBatisJUnitPlatform.JUNIT_5)
                instanceof MyBatisMapperTestRequestFactory.Result.Failure);
    }

    private static String expression(String type) {
        return MyBatisMapperTestSkeletonGenerator.defaultExpression(type);
    }
}
