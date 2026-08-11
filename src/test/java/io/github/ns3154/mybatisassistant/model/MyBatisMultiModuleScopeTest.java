package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.VfsTestUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.refactoring.rename.RenameProcessor;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolver;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import io.github.ns3154.mybatisassistant.resolve.MyBatisProviderMethodResolver;
import io.github.ns3154.mybatisassistant.inspection.MyBatisDuplicateStatementInspection;
import io.github.ns3154.mybatisassistant.inspection.MyBatisInvalidNamespaceInspection;
import io.github.ns3154.mybatisassistant.inspection.MyBatisUnusedStatementInspection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class MyBatisMultiModuleScopeTest extends HeavyPlatformTestCase {
    private final Map<String, VirtualFile> moduleRoots = new HashMap<>();
    private Path modulesPath;
    private VirtualFile modulesRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        modulesPath = Files.createTempDirectory("mybatis-assistant-modules-");
        modulesRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(modulesPath);
        assertNotNull(modulesRoot);
        ModuleRootModificationUtil.updateModel(getModule(), model -> {
            for (ContentEntry entry : model.getContentEntries()) {
                model.removeContentEntry(entry);
            }
        });
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            moduleRoots.clear();
            if (modulesPath != null) {
                FileUtil.delete(modulesPath.toFile());
            }
        }
    }

    public void testStatementResolutionFollowsModuleDependenciesAndInvalidatesOnRootChange()
            throws Exception {
        Module app = addModule("app");
        Module visibleXml = addModule("visibleXml");
        Module unrelatedXml = addModule("unrelatedXml");
        PsiMethod method = addMapperMethod("app");
        addMapperXml("visibleXml", "visible");
        addMapperXml("unrelatedXml", "unrelated");
        assertEquals(app, ModuleUtilCore.findModuleForPsiElement(method));

        assertInstanceOf(resolve(method), MyBatisStatementResolution.NoMapperXml.class);

        ModuleRootModificationUtil.addDependency(app, visibleXml);
        assertInstanceOf(resolve(method), MyBatisStatementResolution.UniqueMatch.class);

        ModuleRootModificationUtil.addDependency(app, unrelatedXml);
        assertInstanceOf(resolve(method), MyBatisStatementResolution.MultipleMatches.class);
    }

    public void testConfigurationAndTypeAliasQueriesExcludeUnrelatedModules() throws Exception {
        Module app = addModule("app");
        Module configuration = addModule("configuration");
        Module unrelated = addModule("unrelated");
        PsiFile context = addModuleFile("app", "src/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addModuleFile("configuration", "src/com/example/domain/User.java", """
                package com.example.domain;
                public final class User {}
                """);
        addModuleFile("configuration", "resources/application.properties", """
                mybatis.type-aliases-package=com.example.domain
                """);
        addModuleFile("unrelated", "resources/application.properties", """
                mybatis.type-aliases-package=com.unrelated.domain
                """);
        assertEquals(app, ModuleUtilCore.findModuleForPsiElement(context));

        assertEmpty(configuration(context).typeAliasPackages());

        ModuleRootModificationUtil.addDependency(app, configuration);
        assertEquals(
                java.util.List.of("com.example.domain"),
                configuration(context).typeAliasPackages());

        PsiClass contextClass = ((PsiJavaFile) context).getClasses()[0];
        MyBatisTypeAliasResolution aliasResolution = ReadAction.compute(
                () -> MyBatisTypeAliasResolver.resolve(contextClass, "user"));
        assertInstanceOf(aliasResolution, MyBatisTypeAliasResolution.Unique.class);
        assertEquals(
                "com.example.domain.User",
                ((MyBatisTypeAliasResolution.Unique) aliasResolution).canonicalType());
        assertFalse(app.isDisposed());
        assertFalse(unrelated.isDisposed());
    }

    public void testMapperScanEvidenceFollowsCallingModuleScope() throws Exception {
        Module app = addModule("app");
        Module scanConfiguration = addModule("scanConfiguration");
        PsiFile mapperFile = addModuleFile("app", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {}
                """);
        addModuleFile(
                "scanConfiguration",
                "src/org/mybatis/spring/annotation/MapperScan.java",
                """
                        package org.mybatis.spring.annotation;
                        public @interface MapperScan { String[] value() default {}; }
                        """);
        addModuleFile("scanConfiguration", "src/com/config/Application.java", """
                package com.config;
                import org.mybatis.spring.annotation.MapperScan;
                @MapperScan("com.example")
                public final class Application {}
                """);
        PsiClass mapper = ((PsiJavaFile) mapperFile).getClasses()[0];

        assertInstanceOf(mapperModel(mapper), MyBatisMapperModelResolution.NotMapper.class);

        ModuleRootModificationUtil.addDependency(app, scanConfiguration);
        assertInstanceOf(mapperModel(mapper), MyBatisMapperModelResolution.Found.class);
    }

    public void testReverseNavigationFollowsXmlModuleDependencies() throws Exception {
        Module app = addModule("app");
        Module visibleMapper = addModule("visibleMapper");
        Module unrelatedMapper = addModule("unrelatedMapper");
        PsiFile xml = addModuleFile("app", "resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        addModuleFile("visibleMapper", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findById(Long id); }
                """);
        addModuleFile("unrelatedMapper", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findById(Long id); }
                """);

        assertEmpty(mapperMethods(xml));

        ModuleRootModificationUtil.addDependency(app, visibleMapper);
        assertSize(1, mapperMethods(xml));

        ModuleRootModificationUtil.addDependency(app, unrelatedMapper);
        assertSize(2, mapperMethods(xml));
    }

    public void testXmlReferencesFollowModuleDependenciesAndRootChanges() throws Exception {
        Module app = addModule("app");
        Module visible = addModule("visible");
        Module unrelated = addModule("unrelated");
        XmlFile appXml = (XmlFile) addModuleFile("app", "resources/mapper/AppMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">
                        select <include refid="com.example.CommonMapper.columns"/> from users
                    </select>
                </mapper>
                """);
        addModuleFile("visible", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        addModuleFile("unrelated", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        addModuleFile("visible", "resources/mapper/CommonMapper.xml", """
                <mapper namespace="com.example.CommonMapper">
                    <sql id="columns">id</sql>
                </mapper>
                """);
        addModuleFile("unrelated", "resources/mapper/CommonMapper.xml", """
                <mapper namespace="com.example.CommonMapper">
                    <sql id="columns">other_id</sql>
                </mapper>
                """);
        PsiReference namespaceReference = xmlReference(appXml, "namespace");
        PsiReference refidReference = xmlReference(appXml, "refid");

        assertEmpty(multiResolve(namespaceReference));
        assertEmpty(multiResolve(refidReference));

        ModuleRootModificationUtil.addDependency(app, visible);
        assertSize(1, multiResolve(namespaceReference));
        assertSize(1, multiResolve(refidReference));

        ModuleRootModificationUtil.addDependency(app, unrelated);
        assertSize(2, multiResolve(namespaceReference));
        assertSize(2, multiResolve(refidReference));
    }

    public void testParentMethodNavigationDiscoversDependentMapperAfterRootChange()
            throws Exception {
        Module base = addModule("base");
        Module app = addModule("app");
        PsiFile baseFile = addModuleFile("base", "src/com/example/BaseMapper.java", """
                package com.example;
                public interface BaseMapper {
                    Object findById(Long id);
                }
                """);
        addModuleFile("app", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper extends com.example.BaseMapper {
                }
                """);
        addModuleFile("app", "resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod parentMethod = ((PsiJavaFile) baseFile).getClasses()[0].getMethods()[0];

        assertInstanceOf(resolve(parentMethod), MyBatisStatementResolution.NoMapperXml.class);

        ModuleRootModificationUtil.addDependency(app, base);
        assertInstanceOf(resolve(parentMethod), MyBatisStatementResolution.UniqueMatch.class);
    }

    public void testProviderMethodNavigationFollowsJavaModuleDependencies() throws Exception {
        Module app = addModule("app");
        Module providers = addModule("providers");
        addModuleFile(
                "app",
                "src/org/apache/ibatis/annotations/SelectProvider.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface SelectProvider {
                            Class<?> type();
                            String method();
                        }
                        """);
        PsiFile mapperFile = addModuleFile("app", "src/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.SelectProvider;
                public interface UserMapper {
                    @SelectProvider(type = com.shared.UserSqlProvider.class, method = "findSql")
                    Object findById(Long id);
                }
                """);
        addModuleFile("providers", "src/com/shared/UserSqlProvider.java", """
                package com.shared;
                public final class UserSqlProvider {
                    public static String findSql(Long id) { return "select 1"; }
                }
                """);
        PsiMethod mapperMethod = ((PsiJavaFile) mapperFile).getClasses()[0].getMethods()[0];

        assertEmpty(providerMethods(mapperMethod));

        ModuleRootModificationUtil.addDependency(app, providers);
        assertSize(1, providerMethods(mapperMethod));
        assertEquals("com.shared.UserSqlProvider",
                providerMethods(mapperMethod).getFirst().getContainingClass().getQualifiedName());
    }

    public void testXmlInspectionsFollowModuleDependenciesAndRootChanges() throws Exception {
        Module app = addModule("app");
        Module mapperModule = addModule("mapperModule");
        XmlFile appXml = (XmlFile) addModuleFile(
                "app",
                "resources/mapper/AppMapper.xml",
                """
                        <mapper namespace="com.example.UserMapper">
                            <select id="findAll">select 1</select>
                        </mapper>
                        """);
        addModuleFile("mapperModule", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findAll(); }
                """);
        addModuleFile("mapperModule", "resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll">select 2</select>
                </mapper>
                """);
        XmlTag root = appXml.getRootTag();
        assertNotNull(root);
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(statement);

        assertSize(1, inspect(new MyBatisInvalidNamespaceInspection(), appXml, root)
                .getResults());
        assertEmpty(inspect(new MyBatisDuplicateStatementInspection(), appXml, statement)
                .getResults());
        assertEmpty(inspect(new MyBatisUnusedStatementInspection(), appXml, statement)
                .getResults());

        ModuleRootModificationUtil.addDependency(app, mapperModule);
        assertEmpty(inspect(new MyBatisInvalidNamespaceInspection(), appXml, root)
                .getResults());
        assertSize(1, inspect(new MyBatisDuplicateStatementInspection(), appXml, statement)
                .getResults());
        assertEmpty(inspect(new MyBatisUnusedStatementInspection(), appXml, statement)
                .getResults());
    }

    public void testMapperMethodRenameDoesNotCrossIntoUnrelatedModule() throws Exception {
        addModule("app");
        addModule("unrelated");
        PsiFile mapperFile = addModuleFile("app", "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { Object findById(Long id); }
                """);
        PsiFile appXml = addModuleFile("app", "resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiFile unrelatedXml = addModuleFile(
                "unrelated",
                "resources/mapper/UserMapper.xml",
                """
                        <mapper namespace="com.example.UserMapper">
                            <select id="findById">select 2</select>
                        </mapper>
                        """);
        PsiMethod method = ((PsiJavaFile) mapperFile).getClasses()[0].getMethods()[0];

        new RenameProcessor(getProject(), method, "findRenamed", false, false).run();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        assertTrue(mapperFile.getText().contains("findRenamed("));
        assertTrue(appXml.getText().contains("id=\"findRenamed\""));
        assertTrue("无依赖模块中的同名 XML 不得被跨模块误写",
                unrelatedXml.getText().contains("id=\"findById\""));
    }

    private Module addModule(String name) throws Exception {
        VirtualFile root = VfsTestUtil.createDir(modulesRoot, name);
        moduleRoots.put(name, root);
        Module module = PsiTestUtil.addModule(
                getProject(),
                JavaModuleType.getModuleType(),
                name,
                root);
        PsiTestUtil.addSourceRoot(
                module,
                VfsTestUtil.createDir(root, "src"));
        PsiTestUtil.addResourceContentToRoots(
                module,
                VfsTestUtil.createDir(root, "resources"),
                false);
        return module;
    }

    private PsiMethod addMapperMethod(String moduleName) {
        PsiFile file = addModuleFile(moduleName, "src/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {
                    Object findById(Long id);
                }
                """);
        return ((PsiJavaFile) file).getClasses()[0].getMethods()[0];
    }

    private void addMapperXml(String moduleName, String marker) {
        addModuleFile(moduleName, "resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select '%s'</select>
                </mapper>
                """.formatted(marker));
    }

    private PsiFile addModuleFile(String moduleName, String relativePath, String content) {
        VirtualFile root = moduleRoots.get(moduleName);
        assertNotNull(root);
        VirtualFile file = VfsTestUtil.createFile(root, relativePath, content);
        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
        assertNotNull(psiFile);
        return psiFile;
    }

    private MyBatisStatementResolution resolve(PsiMethod method) {
        return ReadAction.compute(() -> MyBatisStatementResolver.resolve(method));
    }

    private MyBatisMapperModelResolution mapperModel(PsiClass mapper) {
        return ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(mapper));
    }

    private java.util.List<PsiMethod> mapperMethods(PsiFile xml) {
        return ReadAction.compute(() -> MyBatisMapperMethodResolver.find(
                xml,
                "com.example.UserMapper",
                "findById"));
    }

    private java.util.List<PsiMethod> providerMethods(PsiMethod mapperMethod) {
        return ReadAction.compute(() -> MyBatisProviderMethodResolver.find(mapperMethod));
    }

    private MyBatisProjectConfigurationModel configuration(PsiFile context) {
        MyBatisProjectConfigurationResolution resolution = ReadAction.compute(
                () -> MyBatisProjectConfigurationResolver.resolve(context));
        assertInstanceOf(resolution, MyBatisProjectConfigurationResolution.Found.class);
        return ((MyBatisProjectConfigurationResolution.Found) resolution).model();
    }

    private ProblemsHolder inspect(
            LocalInspectionTool inspection,
            XmlFile file,
            XmlTag tag) {
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = inspection.buildVisitor(holder, true);
        ReadAction.run(() -> tag.accept(visitor));
        return holder;
    }

    private PsiReference xmlReference(XmlFile file, String attributeName) {
        return ReadAction.compute(() -> PsiTreeUtil.findChildrenOfType(file, XmlAttribute.class)
                .stream()
                .filter(attribute -> attributeName.equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .map(XmlAttributeValue::getReferences)
                .filter(references -> references.length == 1)
                .map(references -> references[0])
                .findFirst()
                .orElseThrow());
    }

    private ResolveResult[] multiResolve(PsiReference reference) {
        assertTrue(reference instanceof PsiPolyVariantReference);
        return ReadAction.compute(
                () -> ((PsiPolyVariantReference) reference).multiResolve(false));
    }
}
