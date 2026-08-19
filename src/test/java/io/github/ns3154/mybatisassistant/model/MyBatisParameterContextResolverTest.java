package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisParameterContextResolverTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addStubs();
    }

    public void testSingleObjectUsesDirectRootAndParameterObject() {
        PsiClass mapper = addMapper("""
                package com.example;
                public interface UserMapper {
                    User find(User user);
                }
                final class User { String name; }
                """);

        MyBatisParameterContext context = found(mapper, method(mapper, "find"));

        assertEquals(MyBatisParameterRootMode.DIRECT, context.rootMode());
        assertEquals("com.example.User", context.directType().getCanonicalText());
        assertFalse(context.dynamicMapRoot());
        assertBinding(context, "_parameter", 0, 0,
                MyBatisParameterBindingKind.PARAMETER_OBJECT,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertEmpty(context.findBindings("user"));
    }

    public void testSingleCollectionListArrayAndMapUseConservativeRoots() {
        PsiClass mapper = addMapper("""
                package com.example;
                public interface UserMapper {
                    void list(java.util.List<User> users);
                    void collection(java.util.Collection<User> users);
                    void array(User[] users);
                    void map(java.util.Map<String, User> users);
                }
                final class User {}
                """);

        MyBatisParameterContext list = found(mapper, method(mapper, "list"));
        assertBinding(list, "collection", 0, 0,
                MyBatisParameterBindingKind.COLLECTION,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(list, "list", 0, 0,
                MyBatisParameterBindingKind.LIST,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(list, "users", 0, 0,
                MyBatisParameterBindingKind.ACTUAL_NAME,
                MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);

        MyBatisParameterContext collection = found(mapper, method(mapper, "collection"));
        assertBinding(collection, "collection", 0, 0,
                MyBatisParameterBindingKind.COLLECTION,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertEmpty(collection.findBindings("list"));

        MyBatisParameterContext array = found(mapper, method(mapper, "array"));
        assertBinding(array, "array", 0, 0,
                MyBatisParameterBindingKind.ARRAY,
                MyBatisParameterBindingCertainty.DEFINITE);

        MyBatisParameterContext map = found(mapper, method(mapper, "map"));
        assertTrue(map.dynamicMapRoot());
        assertEmpty(map.findBindings("users"));
    }

    public void testNamedContextProvidesExplicitActualGenericAndFallbackNames() {
        PsiClass mapper = addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    void find(@Param("name") String source, int limit);
                }
                """);

        MyBatisParameterContext context = found(mapper, method(mapper, "find"));

        assertEquals(MyBatisParameterRootMode.NAMED, context.rootMode());
        assertBinding(context, "name", 0, 0,
                MyBatisParameterBindingKind.EXPLICIT,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(context, "limit", 1, 1,
                MyBatisParameterBindingKind.ACTUAL_NAME,
                MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
        assertBinding(context, "param1", 0, 0,
                MyBatisParameterBindingKind.GENERIC_NAME,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(context, "param2", 1, 1,
                MyBatisParameterBindingKind.GENERIC_NAME,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertEmpty(context.findBindings("arg0"));
        assertBinding(context, "arg1", 1, 1,
                MyBatisParameterBindingKind.REFLECTION_FALLBACK,
                MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
        assertEmpty(context.findBindings("0"));
    }

    public void testExplicitNameWinsGenericNameCollision() {
        PsiClass mapper = addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    void find(@Param("param2") String first, int second);
                }
                """);

        MyBatisParameterContext context = found(mapper, method(mapper, "find"));

        List<MyBatisParameterBinding> param2 = context.findBindings("param2");
        assertSize(1, param2);
        assertEquals(0, param2.getFirst().javaIndex());
        assertEquals(MyBatisParameterBindingKind.EXPLICIT, param2.getFirst().kind());
        assertBinding(context, "param1", 0, 0,
                MyBatisParameterBindingKind.GENERIC_NAME,
                MyBatisParameterBindingCertainty.DEFINITE);
    }

    public void testSpecialParametersDoNotAffectEffectiveNumbering() {
        PsiClass mapper = addMapper("""
                package com.example;
                import org.apache.ibatis.session.ResultHandler;
                import org.apache.ibatis.session.RowBounds;
                public interface UserMapper {
                    void find(String first, RowBounds bounds, int second, ResultHandler handler);
                }
                """);

        MyBatisParameterContext context = found(mapper, method(mapper, "find"));

        assertBinding(context, "param1", 0, 0,
                MyBatisParameterBindingKind.GENERIC_NAME,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(context, "param2", 2, 1,
                MyBatisParameterBindingKind.GENERIC_NAME,
                MyBatisParameterBindingCertainty.DEFINITE);
        assertBinding(context, "arg2", 2, 1,
                MyBatisParameterBindingKind.REFLECTION_FALLBACK,
                MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
        assertEmpty(context.findBindings("arg1"));
        assertEmpty(context.findBindings("bounds"));
        assertEmpty(context.findBindings("handler"));
    }

    public void testInheritedGenericParameterIsSubstitutedForCurrentMapper() {
        PsiClass mapper = addMapper("""
                package com.example;
                interface ParentMapper<T> { void save(T entity); }
                public interface UserMapper extends ParentMapper<User> {}
                final class User {}
                """);
        PsiMethod inherited = method(mapper, "save");

        MyBatisParameterContext context = found(mapper, inherited);

        assertEquals("com.example.User", context.directType().getCanonicalText());
        assertEquals("com.example.User", context.bindings().getFirst().type().getCanonicalText());
    }

    public void testUnsupportedDumbDeletedAndCanceledStates() throws Throwable {
        PsiClass mapper = addMapper("""
                package com.example;
                public interface UserMapper {
                    default void local(String first, String second) {}
                    void find(String first, String second);
                }
                """);
        PsiMethod local = method(mapper, "local");
        PsiMethod find = method(mapper, "find");

        assertInstanceOf(resolve(mapper, local),
                MyBatisParameterContextResolution.UnsupportedSource.class);
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                resolve(mapper, find),
                MyBatisParameterContextResolution.IndexNotReady.class));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return resolve(mapper, find);
                    },
                    indicator);
            fail("取消后的参数解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台控制流，不能转换成空上下文。
        }

        PsiFile file = mapper.getContainingFile();
        WriteCommandAction.runWriteCommandAction(getProject(), file::delete);
        assertInstanceOf(resolve(mapper, find),
                MyBatisParameterContextResolution.SourceInvalid.class);
    }

    private PsiClass addMapper(String source) {
        PsiClass mapper = PsiTreeUtil.findChildrenOfType(
                        myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", source),
                        PsiClass.class)
                .stream()
                .filter(candidate -> "UserMapper".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        return mapper;
    }

    private PsiMethod method(PsiClass mapper, String name) {
        return List.of(mapper.findMethodsByName(name, true)).getFirst();
    }

    private MyBatisParameterContext found(PsiClass mapper, PsiMethod method) {
        MyBatisParameterContextResolution resolution = resolve(mapper, method);
        assertInstanceOf(resolution, MyBatisParameterContextResolution.Found.class);
        return ((MyBatisParameterContextResolution.Found) resolution).context();
    }

    private MyBatisParameterContextResolution resolve(PsiClass mapper, PsiMethod method) {
        return ReadAction.compute(() -> MyBatisParameterContextResolver.resolve(mapper, method));
    }

    private void assertBinding(
            MyBatisParameterContext context,
            String name,
            int javaIndex,
            int effectiveIndex,
            MyBatisParameterBindingKind kind,
            MyBatisParameterBindingCertainty certainty) {
        List<MyBatisParameterBinding> matches = context.findBindings(name).stream()
                .filter(binding -> binding.kind() == kind)
                .toList();
        assertSize(1, matches);
        MyBatisParameterBinding binding = matches.getFirst();
        assertEquals(javaIndex, binding.javaIndex());
        assertEquals(effectiveIndex, binding.effectiveIndex());
        assertEquals(certainty, binding.certainty());
    }

    private void addStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/session/RowBounds.java",
                """
                        package org.apache.ibatis.session;
                        public class RowBounds {}
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/session/ResultHandler.java",
                """
                        package org.apache.ibatis.session;
                        public interface ResultHandler {}
                        """);
    }
}
