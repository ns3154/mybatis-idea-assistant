package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisMapperModelResolverTest extends BasePlatformTestCase {
    public void testBuildsXmlMapperMethodsParametersAndAnnotationSources() {
        addMyBatisAnnotationStubs();
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    User findById(@Param("userId") Long id);

                    @Select("select 1")
                    int countUsers();

                    @SelectProvider(type = Provider.class, method = "sql")
                    User dynamicUser(Long id);

                    static void ignoredStatic() {}

                    default void ignoredDefault() {}
                }

                final class User {}
                final class Provider {}
                """);
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        MyBatisMapperModel model = found(mapper);

        assertEquals("com.example.UserMapper", model.qualifiedName());
        assertEquals(MyBatisMapperEvidenceKind.XML_NAMESPACE, model.evidence().getFirst().kind());
        assertEquals(3, model.methods().size());
        MyBatisMapperMethodModel findById = method(model, "findById");
        assertEquals("com.example.User", findById.returnType());
        assertEquals(MyBatisStatementSourceKind.XML, findById.statementSource());
        assertEquals("userId", findById.parameters().getFirst().name());
        assertEquals("Long", findById.parameters().getFirst().canonicalType());
        assertTrue(findById.parameters().getFirst().explicitlyNamed());
        assertEquals(MyBatisStatementSourceKind.ANNOTATION_SQL, method(model, "countUsers").statementSource());
        assertEquals(MyBatisStatementSourceKind.PROVIDER, method(model, "dynamicUser").statementSource());
    }

    public void testRecognizesMapperAnnotationWithoutXml() {
        addMyBatisAnnotationStubs();
        PsiClass mapper = addJava("src/main/java/com/example/AnnotatedMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Mapper;

                @Mapper
                public interface AnnotatedMapper {
                    void save(String value);
                }
                """);

        MyBatisMapperModel model = found(mapper);

        assertEquals(1, model.evidence().size());
        assertEquals(MyBatisMapperEvidenceKind.MAPPER_ANNOTATION, model.evidence().getFirst().kind());
        assertEquals("save(String)", model.methods().getFirst().stableSignature());
    }

    public void testSubstitutesInheritedGenericMethods() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;

                interface ParentMapper<T, ID> {
                    T find(ID id);
                }

                public interface UserMapper extends ParentMapper<User, Long> {
                }

                final class User {}
                """);
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """);

        MyBatisMapperMethodModel inherited = method(found(mapper), "find");

        assertTrue(inherited.inherited());
        assertEquals("com.example.ParentMapper", inherited.declaringType());
        assertEquals("com.example.User", inherited.returnType());
        assertEquals("Long", inherited.parameters().getFirst().canonicalType());
    }

    public void testBuildsTypedEntitiesForContainersArraysAndParameters() {
        PsiClass mapper = addJava("src/main/java/com/example/EntityMapper.java", """
                package com.example;

                public interface EntityMapper {
                    java.util.List<User> listUsers(java.util.Map<String, User> filters);
                    java.util.Optional<User> optionalUser();
                    User[] userArray();
                    int count();
                }

                final class User {}
                """);
        addMapperXml("mapper/EntityMapper.xml", """
                <mapper namespace="com.example.EntityMapper"/>
                """);

        MyBatisMapperModel model = found(mapper);
        MyBatisEntityModel list = method(model, "listUsers").returnEntity();
        assertEquals(MyBatisEntityKind.COLLECTION, list.kind());
        assertEquals("java.util.List", list.qualifiedName());
        assertEquals("com.example.User", list.typeArguments().getFirst().qualifiedName());
        MyBatisEntityModel filters = method(model, "listUsers")
                .parameters().getFirst().entity();
        assertEquals(MyBatisEntityKind.MAP, filters.kind());
        assertEquals(2, filters.typeArguments().size());
        assertEquals(MyBatisEntityKind.OPTIONAL, method(model, "optionalUser").returnEntity().kind());
        assertEquals(MyBatisEntityKind.ARRAY, method(model, "userArray").returnEntity().kind());
        assertEquals("com.example.User", method(model, "userArray")
                .returnEntity().typeArguments().getFirst().qualifiedName());
        assertEquals(MyBatisEntityKind.PRIMITIVE, method(model, "count").returnEntity().kind());
    }

    public void testUnrelatedJavaAndXmlChangesReuseSemanticModelSnapshot() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { void save(String value); }
                """);
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        MyBatisMapperModelResolution first = resolve(mapper);

        addJava("src/main/java/com/example/Unrelated.java", """
                package com.example;
                public final class Unrelated { int value() { return 1; } }
                """);
        addMapperXml("mapper/Unrelated.xml", """
                <mapper namespace="com.example.UnrelatedMapper"/>
                """);
        MyBatisMapperModelResolution second = resolve(mapper);

        assertSame(first, second);
    }

    public void testMapperSourceChangeRebuildsSemanticModel() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper { void first(); }
                """);
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        MyBatisMapperModelResolution first = resolve(mapper);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            mapper.add(JavaPsiFacade.getElementFactory(getProject()).createMethodFromText(
                    "void second();",
                    mapper));
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });
        MyBatisMapperModelResolution second = resolve(mapper);

        assertNotSame(first, second);
        assertEquals(2, ((MyBatisMapperModelResolution.Found) second).model().methods().size());
    }

    public void testRejectsOrdinaryInterfacesClassesAndNonClassSources() {
        PsiFile file = myFixture.addFileToProject("src/main/java/com/example/Ordinary.java", """
                package com.example;
                interface Ordinary {}
                final class OrdinaryClass {}
                """);
        List<PsiClass> classes = List.copyOf(PsiTreeUtil.findChildrenOfType(file, PsiClass.class));

        assertInstanceOf(
                ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(classes.getFirst())),
                MyBatisMapperModelResolution.NotMapper.class);
        assertInstanceOf(
                ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(classes.get(1))),
                MyBatisMapperModelResolution.UnsupportedSource.class);
        assertInstanceOf(
                ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(file)),
                MyBatisMapperModelResolution.UnsupportedSource.class);
    }

    public void testPreservesMultipleXmlSourcesAsEvidence() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {}
                """);
        addMapperXml("mapper/one/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        addMapperXml("mapper/two/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);

        MyBatisMapperEvidence evidence = found(mapper).evidence().getFirst();

        assertEquals(MyBatisMapperEvidenceKind.XML_NAMESPACE, evidence.kind());
        assertEquals("com.example.UserMapper#sources=2", evidence.detail());
    }

    public void testRecognizesMapperScanPackageWithoutSpringPluginApi() {
        addMapperScanStub();
        addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan("com.example.mapper")
                public class Application {}
                """);
        PsiClass mapper = addJava("src/main/java/com/example/mapper/ScannedMapper.java", """
                package com.example.mapper;
                public interface ScannedMapper {
                    void save(Object value);
                }
                """);
        PsiClass outside = addJava("src/main/java/com/example/mapperExtra/OutsideMapper.java", """
                package com.example.mapperExtra;
                public interface OutsideMapper {}
                """);

        MyBatisMapperModel model = found(mapper);

        assertEquals(MyBatisMapperEvidenceKind.MAPPER_SCAN, model.evidence().getFirst().kind());
        assertEquals("com.example.app.Application#package=com.example.mapper", model.evidence().getFirst().detail());
        assertInstanceOf(resolve(outside), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testMapperScanAnnotationFilterDoesNotAcceptUnmarkedInterface() {
        addMapperScanStub();
        PsiClass application = addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import com.example.marker.MapperMarker;
                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan(basePackages = "com.example.mapper", annotationClass = MapperMarker.class)
                public class Application {}
                """);
        myFixture.addFileToProject("src/main/java/com/example/marker/MapperMarker.java", """
                package com.example.marker;
                public @interface MapperMarker {}
                """);
        PsiClass included = addJava("src/main/java/com/example/mapper/IncludedMapper.java", """
                package com.example.mapper;
                import com.example.marker.MapperMarker;
                @MapperMarker
                public interface IncludedMapper {}
                """);
        PsiClass excluded = addJava("src/main/java/com/example/mapper/ExcludedMapper.java", """
                package com.example.mapper;
                public interface ExcludedMapper {}
                """);
        PsiAnnotation mapperScan = application.getAnnotation("org.mybatis.spring.annotation.MapperScan");
        assertNotNull(mapperScan);
        assertNotNull(mapperScan.findDeclaredAttributeValue("annotationClass"));

        assertInstanceOf(resolve(included), MyBatisMapperModelResolution.Found.class);
        assertInstanceOf(resolve(excluded), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testMapperScanMarkerInterfaceFilterAndDefaultPackage() {
        addMapperScanStub();
        myFixture.addFileToProject("src/main/java/com/example/app/Marker.java", """
                package com.example.app;
                public interface Marker {}
                """);
        PsiClass application = addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan(markerInterface = com.example.app.Marker.class)
                public class Application {}
                """);
        PsiClass included = addJava("src/main/java/com/example/app/mapper/IncludedMapper.java", """
                package com.example.app.mapper;
                import com.example.app.Marker;
                public interface IncludedMapper extends Marker {}
                """);
        PsiClass excluded = addJava("src/main/java/com/example/app/mapper/ExcludedMapper.java", """
                package com.example.app.mapper;
                public interface ExcludedMapper {}
                """);
        PsiAnnotation mapperScan = application.getAnnotation("org.mybatis.spring.annotation.MapperScan");
        assertNotNull(mapperScan);
        assertNotNull(mapperScan.findDeclaredAttributeValue("markerInterface"));

        assertInstanceOf(resolve(included), MyBatisMapperModelResolution.Found.class);
        MyBatisMapperModelResolution excludedResolution = resolve(excluded);
        assertInstanceOf(excludedResolution, MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testUnresolvedMapperScanFilterDoesNotAcceptWholePackage() {
        addMapperScanStub();
        addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan(basePackages = "com.example.mapper", markerInterface = MissingMarker.class)
                public class Application {}
                """);
        PsiClass mapper = addJava("src/main/java/com/example/mapper/UserMapper.java", """
                package com.example.mapper;
                public interface UserMapper {}
                """);

        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testMapperScanBasePackageClassUsesItsPackage() {
        addMapperScanStub();
        myFixture.addFileToProject("src/main/java/com/example/mapper/MapperAnchor.java", """
                package com.example.mapper;
                public final class MapperAnchor {}
                """);
        addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan(basePackageClasses = com.example.mapper.MapperAnchor.class)
                public class Application {}
                """);
        PsiClass mapper = addJava("src/main/java/com/example/mapper/UserMapper.java", """
                package com.example.mapper;
                public interface UserMapper {}
                """);

        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.Found.class);
    }

    public void testMapperScansContainerAndExplicitSentinelFilters() {
        addMapperScanStub();
        PsiClass application = addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;
                import org.mybatis.spring.annotation.MapperScans;

                @MapperScans({
                    @MapperScan(
                        basePackages = "com.example.one",
                        annotationClass = java.lang.annotation.Annotation.class),
                    @MapperScan(
                        basePackages = "com.example.two",
                        markerInterface = java.lang.Class.class)
                })
                public class Application {}
                """);
        PsiAnnotation container = application.getAnnotation(
                "org.mybatis.spring.annotation.MapperScans");
        assertNotNull(container);
        PsiAnnotationMemberValue containerValue = container.findAttributeValue("value");
        assertInstanceOf(containerValue, PsiArrayInitializerMemberValue.class);
        for (PsiAnnotationMemberValue initializer
                : ((PsiArrayInitializerMemberValue) containerValue).getInitializers()) {
            assertInstanceOf(initializer, PsiAnnotation.class);
            assertEquals(
                    "org.mybatis.spring.annotation.MapperScan",
                    ((PsiAnnotation) initializer).getQualifiedName());
        }
        PsiClass containerClass = JavaPsiFacade.getInstance(getProject()).findClass(
                "org.mybatis.spring.annotation.MapperScans",
                GlobalSearchScope.allScope(getProject()));
        assertNotNull(containerClass);
        assertContainsElements(
                AnnotatedElementsSearch.searchPsiClasses(
                        containerClass,
                        GlobalSearchScope.projectScope(getProject())).findAll(),
                application);
        PsiClass first = addJava("src/main/java/com/example/one/FirstMapper.java", """
                package com.example.one;
                public interface FirstMapper {}
                """);
        PsiClass second = addJava("src/main/java/com/example/two/SecondMapper.java", """
                package com.example.two;
                public interface SecondMapper {}
                """);

        assertInstanceOf(resolve(first), MyBatisMapperModelResolution.Found.class);
        assertInstanceOf(resolve(second), MyBatisMapperModelResolution.Found.class);
    }

    public void testRecognizesMapperClassAndPackageFromMyBatisConfiguration() {
        PsiClass explicit = addJava("src/main/java/com/example/explicit/ExplicitMapper.java", """
                package com.example.explicit;
                public interface ExplicitMapper {}
                """);
        PsiClass packaged = addJava("src/main/java/com/example/scanned/PackagedMapper.java", """
                package com.example.scanned;
                public interface PackagedMapper {}
                """);
        PsiClass outside = addJava("src/main/java/com/example/scannedExtra/OutsideMapper.java", """
                package com.example.scannedExtra;
                public interface OutsideMapper {}
                """);
        myFixture.addFileToProject("src/main/resources/mybatis-config.xml", """
                <configuration><mappers>
                    <mapper class="com.example.explicit.ExplicitMapper"/>
                    <package name="com.example.scanned"/>
                </mappers></configuration>
                """);

        assertEquals(
                MyBatisMapperEvidenceKind.MYBATIS_CONFIGURATION,
                found(explicit).evidence().getFirst().kind());
        assertEquals(
                MyBatisMapperEvidenceKind.MYBATIS_CONFIGURATION,
                found(packaged).evidence().getFirst().kind());
        assertInstanceOf(resolve(outside), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testUnsavedMapperScanPackageChangeInvalidatesRegistryAndModel() {
        addMapperScanStub();
        PsiClass application = addJava("src/main/java/com/example/app/Application.java", """
                package com.example.app;

                import org.mybatis.spring.annotation.MapperScan;

                @MapperScan("com.example.mapper")
                public class Application {}
                """);
        PsiClass mapper = addJava("src/main/java/com/example/mapper/UserMapper.java", """
                package com.example.mapper;
                public interface UserMapper {}
                """);
        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.Found.class);
        PsiAnnotation mapperScan = application.getAnnotation("org.mybatis.spring.annotation.MapperScan");
        assertNotNull(mapperScan);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            mapperScan.setDeclaredAttributeValue(
                    "value",
                    JavaPsiFacade.getElementFactory(getProject())
                            .createExpressionFromText("\"com.example.other\"", mapperScan));
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testUnsavedNamespaceChangeInvalidatesCachedRecognition() {
        PsiClass mapper = addJava("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {}
                """);
        PsiFile xml = addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.Found.class);
        XmlTag root = ((XmlFile) xml).getRootTag();
        assertNotNull(root);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            root.setAttribute("namespace", "com.example.OtherMapper");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertInstanceOf(resolve(mapper), MyBatisMapperModelResolution.NotMapper.class);
    }

    public void testDumbModeAndDeletedSourceReturnTypedStates() {
        PsiFile file = myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", """
                package com.example;
                public interface UserMapper {}
                """);
        PsiClass mapper = PsiTreeUtil.findChildOfType(file, PsiClass.class);
        assertNotNull(mapper);
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                resolve(mapper),
                MyBatisMapperModelResolution.IndexNotReady.class));

        WriteCommandAction.runWriteCommandAction(getProject(), file::delete);
        assertInstanceOf(
                resolve(mapper),
                MyBatisMapperModelResolution.SourceInvalid.class);
    }

    private void addMyBatisAnnotationStubs() {
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/annotations/Mapper.java", """
                package org.apache.ibatis.annotations;
                public @interface Mapper {}
                """);
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/annotations/Param.java", """
                package org.apache.ibatis.annotations;
                public @interface Param { String value(); }
                """);
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/annotations/Select.java", """
                package org.apache.ibatis.annotations;
                public @interface Select { String[] value(); }
                """);
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/annotations/SelectProvider.java", """
                package org.apache.ibatis.annotations;
                public @interface SelectProvider { Class<?> type(); String method(); }
                """);
    }

    private void addMapperScanStub() {
        myFixture.addFileToProject("src/main/java/org/mybatis/spring/annotation/MapperScan.java", """
                package org.mybatis.spring.annotation;

                public @interface MapperScan {
                    String[] value() default {};
                    String[] basePackages() default {};
                    Class<?>[] basePackageClasses() default {};
                    Class<?> annotationClass() default java.lang.annotation.Annotation.class;
                    Class<?> markerInterface() default Class.class;
                }
                """);
        myFixture.addFileToProject("src/main/java/org/mybatis/spring/annotation/MapperScans.java", """
                package org.mybatis.spring.annotation;

                public @interface MapperScans {
                    MapperScan[] value();
                }
                """);
    }

    private PsiClass addJava(String path, String source) {
        PsiFile file = myFixture.addFileToProject(path, source);
        String fileName = file.getVirtualFile().getNameWithoutExtension();
        return PsiTreeUtil.findChildrenOfType(file, PsiClass.class).stream()
                .filter(psiClass -> fileName.equals(psiClass.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 Java 类型：" + fileName));
    }

    private PsiFile addMapperXml(String path, String xml) {
        return myFixture.addFileToProject("src/main/resources/" + path, xml);
    }

    private MyBatisMapperModelResolution resolve(PsiClass mapper) {
        return ReadAction.compute(() -> MyBatisMapperModelResolver.resolve(mapper));
    }

    private MyBatisMapperModel found(PsiClass mapper) {
        MyBatisMapperModelResolution resolution = resolve(mapper);
        assertInstanceOf(resolution, MyBatisMapperModelResolution.Found.class);
        return ((MyBatisMapperModelResolution.Found) resolution).model();
    }

    private MyBatisMapperMethodModel method(MyBatisMapperModel model, String name) {
        return model.methods().stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到方法模型：" + name));
    }
}
