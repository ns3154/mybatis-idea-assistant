package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Assert;

import java.util.List;

public final class MyBatisFrameworkMapperResolverTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addFrameworkStubs();
    }

    public void testRecognizesThreeFrameworksAndInfersConcreteEntities() {
        PsiClass plus = addJava("src/main/java/com/example/PlusUserMapper.java", """
                package com.example;
                public interface PlusUserMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<PlusUser> {
                    PlusUser findByName(String name);
                }
                final class PlusUser {}
                """);
        PsiClass flex = addJava("src/main/java/com/example/FlexUserMapper.java", """
                package com.example;
                public interface FlexUserMapper
                        extends com.mybatisflex.core.BaseMapper<FlexUser> {}
                final class FlexUser {}
                """);
        PsiClass tk = addJava("src/main/java/com/example/TkUserMapper.java", """
                package com.example;
                public interface TkUserMapper
                        extends tk.mybatis.mapper.common.Mapper<TkUser> {}
                final class TkUser {}
                """);

        MyBatisMapperModel plusModel = found(plus);
        MyBatisMapperModel flexModel = found(flex);
        MyBatisMapperModel tkModel = found(tk);

        assertBinding(
                plusModel,
                MyBatisFrameworkKind.MYBATIS_PLUS,
                "com.example.PlusUser");
        assertBinding(
                flexModel,
                MyBatisFrameworkKind.MYBATIS_FLEX,
                "com.example.FlexUser");
        assertBinding(tkModel, MyBatisFrameworkKind.TK_MAPPER, "com.example.TkUser");
        assertEquals(List.of("findByName(String)"), plusModel.methods().stream()
                .map(MyBatisMapperMethodModel::stableSignature)
                .toList());
        assertEmpty(flexModel.methods());
        assertEmpty(tkModel.methods());
        assertContainsElements(
                plusModel.frameworkBindings().getFirst().frameworkMethodSignatures(),
                "insert(com.example.PlusUser)",
                "selectById(Object)");
        assertContainsElements(
                plusModel.frameworkBindings().getFirst().frameworkDeclaringTypes(),
                "com.baomidou.mybatisplus.core.mapper.BaseMapper");
    }

    public void testIndirectGenericBaseKeepsCustomMethodButExcludesFrameworkMethods() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                interface CommonMapper<T>
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<T> {
                    T findCustom(Long id);
                }
                public interface UserMapper extends CommonMapper<User> {}
                final class User {}
                """);

        MyBatisMapperModel model = found(mapper);

        assertBinding(model, MyBatisFrameworkKind.MYBATIS_PLUS, "com.example.User");
        assertEquals(List.of("findCustom(Long)"), model.methods().stream()
                .map(MyBatisMapperMethodModel::stableSignature)
                .toList());
        MyBatisMapperMethodModel method = model.methods().getFirst();
        assertTrue(method.inherited());
        assertEquals("com.example.CommonMapper", method.declaringType());
        assertEquals("com.example.User", method.returnType());
    }

    public void testRedeclaredFrameworkSignatureRemainsAnExplicitMapperMethod() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<User> {
                    @Override int insert(User entity);
                }
                final class User {}
                """);

        MyBatisMapperModel model = found(mapper);

        assertEquals(List.of("insert(com.example.User)"), model.methods().stream()
                .map(MyBatisMapperMethodModel::stableSignature)
                .toList());
        assertEquals("com.example.UserMapper", model.methods().getFirst().declaringType());
    }

    public void testRawAndCrossFrameworkInheritanceAreExplicitlyUnsupported() {
        PsiClass raw = addJava("src/main/java/com/example/RawMapper.java", """
                package com.example;
                public interface RawMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper {}
                """);
        PsiClass mixed = addJava("src/main/java/com/example/MixedMapper.java", """
                package com.example;
                public interface MixedMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<User>,
                        com.mybatisflex.core.BaseMapper<User> {}
                final class User {}
                """);

        MyBatisFrameworkMapperResolution rawFramework = framework(raw);
        MyBatisFrameworkMapperResolution mixedFramework = framework(mixed);

        assertInstanceOf(rawFramework, MyBatisFrameworkMapperResolution.Unsupported.class);
        assertTrue(((MyBatisFrameworkMapperResolution.Unsupported) rawFramework)
                .reason().contains("实体泛型"));
        assertInstanceOf(mixedFramework, MyBatisFrameworkMapperResolution.Unsupported.class);
        assertTrue(((MyBatisFrameworkMapperResolution.Unsupported) mixedFramework)
                .reason().contains("多个"));
        assertInstanceOf(resolve(raw), MyBatisMapperModelResolution.UnsupportedSource.class);
        assertInstanceOf(resolve(mixed), MyBatisMapperModelResolution.UnsupportedSource.class);
    }

    public void testOrdinaryInterfaceAndClassRemainTypedNonFrameworkSources() {
        PsiClass ordinary = addJava("src/main/java/com/example/Ordinary.java", """
                package com.example;
                public interface Ordinary {}
                """);
        PsiClass service = addJava("src/main/java/com/example/Service.java", """
                package com.example;
                public final class Service {}
                """);

        assertInstanceOf(
                framework(ordinary),
                MyBatisFrameworkMapperResolution.NotFrameworkMapper.class);
        assertInstanceOf(
                framework(service),
                MyBatisFrameworkMapperResolution.Unsupported.class);
    }

    public void testFrameworkVersionMatrixRejectsUnknownOrUnsupportedVersions() {
        MyBatisFrameworkKind.MYBATIS_PLUS.requireSupportedVersion("3.5.17");
        MyBatisFrameworkKind.MYBATIS_FLEX.requireSupportedVersion("1.11.8");
        MyBatisFrameworkKind.TK_MAPPER.requireSupportedVersion("6.0.0");

        assertTrue(Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisFrameworkKind.MYBATIS_PLUS.requireSupportedVersion("3.4.3"))
                .getMessage().contains("3.5+"));
        assertTrue(Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisFrameworkKind.MYBATIS_FLEX.requireSupportedVersion("1.7.1"))
                .getMessage().contains("1.7.2+"));
        assertTrue(Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisFrameworkKind.TK_MAPPER.requireSupportedVersion("4.2.3"))
                .getMessage().contains("6.x"));
        assertTrue(Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisFrameworkKind.TK_MAPPER.requireSupportedVersion("latest"))
                .getMessage().contains("版本格式"));
    }

    public void testFrameworkBaseChangeInvalidatesCachedModel() {
        PsiClass plusBaseClass = JavaPsiFacade.getInstance(getProject()).findClass(
                "com.baomidou.mybatisplus.core.mapper.BaseMapper",
                getModule().getModuleScope());
        assertNotNull(plusBaseClass);
        PsiFile plusBase = plusBaseClass.getContainingFile();
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<User> {}
                final class User {}
                """);
        MyBatisMapperModelResolution first = resolve(mapper);
        assertContainsElements(
                ((MyBatisMapperModelResolution.Found) first)
                        .model().frameworkBindings().getFirst().frameworkMethodSignatures(),
                "insert(com.example.User)");

        replaceText(plusBase, "int insert(T entity);", "int save(T entity);");
        MyBatisMapperModelResolution second = resolve(mapper);

        assertNotSame(first, second);
        assertContainsElements(
                ((MyBatisMapperModelResolution.Found) second)
                        .model().frameworkBindings().getFirst().frameworkMethodSignatures(),
                "save(com.example.User)");
    }

    public void testDumbModeIsTypedAndCancellationPropagates() throws Throwable {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<User> {}
                final class User {}
                """);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                framework(mapper),
                MyBatisFrameworkMapperResolution.IndexNotReady.class));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return framework(mapper);
                    },
                    indicator);
            fail("取消后的框架 Mapper 解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消属于平台正常控制流，不能降级为未识别。
        }
    }

    private void assertBinding(
            MyBatisMapperModel model,
            MyBatisFrameworkKind framework,
            String entityQualifiedName) {
        assertSize(1, model.frameworkBindings());
        MyBatisFrameworkMapperBinding binding = model.frameworkBindings().getFirst();
        assertEquals(framework, binding.framework());
        assertEquals(entityQualifiedName, binding.entity().qualifiedName());
        assertTrue(model.evidence().stream().anyMatch(evidence ->
                evidence.kind() == MyBatisMapperEvidenceKind.FRAMEWORK_BASE_MAPPER
                        && evidence.detail().contains(entityQualifiedName)));
    }

    private void addFrameworkStubs() {
        myFixture.addFileToProject(
                "src/main/java/com/baomidou/mybatisplus/core/mapper/BaseMapper.java",
                """
                        package com.baomidou.mybatisplus.core.mapper;
                        public interface BaseMapper<T> {
                            int insert(T entity);
                            T selectById(Object id);
                        }
                        """);
        myFixture.addFileToProject(
                "src/main/java/com/mybatisflex/core/BaseMapper.java",
                """
                        package com.mybatisflex.core;
                        public interface BaseMapper<T> {
                            int insert(T entity);
                            T selectOneById(Object id);
                        }
                        """);
        myFixture.addFileToProject(
                "src/main/java/tk/mybatis/mapper/common/Mapper.java",
                """
                        package tk.mybatis.mapper.common;
                        public interface Mapper<T> {
                            int insert(T entity);
                            T selectByPrimaryKey(Object key);
                        }
                        """);
    }

    private PsiClass addJava(String path, String source) {
        PsiFile file = myFixture.addFileToProject(path, source);
        String fileName = file.getVirtualFile().getNameWithoutExtension();
        return PsiTreeUtil.findChildrenOfType(file, PsiClass.class).stream()
                .filter(type -> fileName.equals(type.getName()))
                .findFirst()
                .orElseThrow();
    }

    private void replaceText(PsiFile file, String before, String after) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            var document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
            assertNotNull(document);
            int offset = document.getText().indexOf(before);
            assertTrue(offset >= 0);
            document.replaceString(offset, offset + before.length(), after);
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
    }

    private MyBatisMapperModel found(PsiClass mapper) {
        MyBatisMapperModelResolution resolution = resolve(mapper);
        assertInstanceOf(resolution, MyBatisMapperModelResolution.Found.class);
        return ((MyBatisMapperModelResolution.Found) resolution).model();
    }

    private MyBatisMapperModelResolution resolve(PsiClass mapper) {
        return ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(mapper));
    }

    private MyBatisFrameworkMapperResolution framework(PsiClass mapper) {
        return ReadAction.compute(() -> MyBatisFrameworkMapperResolver.resolve(mapper));
    }
}
