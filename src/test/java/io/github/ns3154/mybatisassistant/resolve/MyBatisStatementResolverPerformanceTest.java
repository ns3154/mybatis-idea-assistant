package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisStatementResolverPerformanceTest extends BasePlatformTestCase {
    private static final List<String> NO_XML_STATEMENT_ANNOTATION_NAMES = List.of(
            "Select",
            "Insert",
            "Update",
            "Delete",
            "SelectProvider",
            "InsertProvider",
            "UpdateProvider",
            "DeleteProvider",
            "Flush");

    public void testRejectsUnsupportedMethodsBeforeIndexLookup() {
        for (String annotationName : NO_XML_STATEMENT_ANNOTATION_NAMES) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/"
                            + annotationName
                            + ".java",
                    """
                            package org.apache.ibatis.annotations;
                            public @interface %s { %s }
                            """.formatted(
                            annotationName,
                            "Select".equals(annotationName) ? "@interface List {}" : ""));
        }

        PsiJavaFile interfaceFile = (PsiJavaFile) myFixture.configureByText(
                "UserMapper.java",
                """
                        package com.example;

                        public interface UserMapper {
                            static Object staticMethod() { return null; }
                            default Object defaultMethod() { return null; }
                            private Object bodyMethod() { return null; }
                            Object overloaded(long id);
                            Object overloaded(String id);
                            @org.apache.ibatis.annotations.Select Object selectMethod();
                            @org.apache.ibatis.annotations.Insert Object insertMethod();
                            @org.apache.ibatis.annotations.Update Object updateMethod();
                            @org.apache.ibatis.annotations.Delete Object deleteMethod();
                            @org.apache.ibatis.annotations.SelectProvider Object selectProviderMethod();
                            @org.apache.ibatis.annotations.InsertProvider Object insertProviderMethod();
                            @org.apache.ibatis.annotations.UpdateProvider Object updateProviderMethod();
                            @org.apache.ibatis.annotations.DeleteProvider Object deleteProviderMethod();
                            @org.apache.ibatis.annotations.Flush Object flushMethod();
                            @org.apache.ibatis.annotations.Select.List Object selectListMethod();
                        }
                        """);
        PsiJavaFile classFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/ConcreteMapper.java",
                """
                        package com.example;
                        public class ConcreteMapper {
                            public Object findById(long id) { return null; }
                        }
                        """);
        PsiJavaFile annotationFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/MapperContract.java",
                """
                        package com.example;
                        public @interface MapperContract {
                            String value();
                        }
                        """);
        CountingLookup lookup = new CountingLookup(List.of(), false);

        ReadAction.run(() -> {
            for (PsiMethod method : PsiTreeUtil.findChildrenOfType(interfaceFile, PsiMethod.class)) {
                MyBatisStatementResolution resolution =
                        MyBatisStatementResolver.resolveUncached(method, lookup);
                assertTrue(method.getName() + " 应在访问索引前被排除，实际结果：" + resolution,
                        resolution instanceof MyBatisStatementResolution.UnsupportedSource);
            }
            assertTrue(MyBatisStatementResolver.resolveUncached(
                            findMethod(classFile, "findById"), lookup)
                    instanceof MyBatisStatementResolution.UnsupportedSource);
            assertTrue(MyBatisStatementResolver.resolveUncached(
                            findMethod(annotationFile, "value"), lookup)
                    instanceof MyBatisStatementResolution.UnsupportedSource);
        });

        assertEmpty(lookup.statementQueries);
        assertEmpty(lookup.namespaceQueries);
    }

    public void testManyMethodsShareCacheAndIgnoreUnrelatedJavaBodyChanges() {
        int methodCount = 50;
        StringBuilder javaSource = new StringBuilder("""
                package com.example;
                public interface BulkMapper {
                """);
        StringBuilder xmlSource = new StringBuilder("""
                <mapper namespace="com.example.BulkMapper">
                """);
        for (int index = 0; index < methodCount; index++) {
            javaSource.append("    Object method").append(index).append("();\n");
            xmlSource.append("    <select id=\"method")
                    .append(index)
                    .append("\">select ")
                    .append(index)
                    .append("</select>\n");
        }
        javaSource.append("}\n");
        xmlSource.append("</mapper>\n");

        PsiJavaFile ordinaryJavaFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/OrdinaryService.java",
                """
                        package com.example;
                        public class OrdinaryService {
                            int calculate() { return 1; /* 初始注释 */ }
                        }
                        """);
        PsiJavaFile mapperFile = (PsiJavaFile) myFixture.configureByText(
                "BulkMapper.java",
                javaSource.toString());
        PsiFile xmlFile = addMapperXml(xmlSource.toString());
        XmlTag firstStatement = ((XmlFile) xmlFile).getRootTag().findFirstSubTag("select");
        assertNotNull(firstStatement);
        List<PsiMethod> methods = List.copyOf(
                PsiTreeUtil.findChildrenOfType(mapperFile, PsiMethod.class));
        assertSize(methodCount, methods);

        CountingLookup countingLookup = new CountingLookup(List.of(firstStatement), true);
        ReadAction.run(() -> {
            for (PsiMethod method : methods) {
                assertTrue(MyBatisStatementResolver.resolveUncached(method, countingLookup)
                        instanceof MyBatisStatementResolution.UniqueMatch);
            }
        });
        assertEquals(methodCount, countingLookup.statementQueries.size());
        assertTrue(countingLookup.statementQueries.stream()
                .allMatch(query -> "com.example.BulkMapper".equals(query.namespace())));
        assertEquals(
                methods.stream().map(PsiMethod::getName).toList(),
                countingLookup.statementQueries.stream()
                        .map(StatementQuery::statementId)
                        .toList());
        assertEmpty(countingLookup.namespaceQueries);

        List<MyBatisStatementResolution> cachedResolutions = ReadAction.compute(() -> methods.stream()
                .map(MyBatisStatementResolver::resolve)
                .toList());
        ReadAction.run(() -> {
            for (int index = 0; index < methodCount; index++) {
                assertSame(cachedResolutions.get(index),
                        MyBatisStatementResolver.resolve(methods.get(index)));
            }
        });

        updateOrdinaryJavaBodyWithoutSaving(ordinaryJavaFile);

        ReadAction.run(() -> {
            for (int index = 0; index < methodCount; index++) {
                assertSame("无关 Java 方法体或注释修改不得驱逐 Mapper 缓存",
                        cachedResolutions.get(index),
                        MyBatisStatementResolver.resolve(methods.get(index)));
            }
        });
    }

    public void testManyMethodOrdinaryInterfaceStaysAtTwoQueriesBeforeCacheHit() {
        int methodCount = 50;
        StringBuilder javaSource = new StringBuilder("""
                package com.example;
                public interface OrdinaryContract {
                """);
        for (int index = 0; index < methodCount; index++) {
            javaSource.append("    Object operation").append(index).append("();\n");
        }
        javaSource.append("}\n");
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText(
                "OrdinaryContract.java",
                javaSource.toString());
        List<PsiMethod> methods = List.copyOf(
                PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class));
        assertSize(methodCount, methods);

        CountingLookup lookup = new CountingLookup(List.of(), false);
        ReadAction.run(() -> {
            for (PsiMethod method : methods) {
                assertTrue(MyBatisStatementResolver.resolveUncached(method, lookup)
                        instanceof MyBatisStatementResolution.NoMapperXml);
            }
        });
        assertEquals(methodCount, lookup.statementQueries.size());
        assertTrue(lookup.statementQueries.stream()
                .allMatch(query -> "com.example.OrdinaryContract".equals(query.namespace())));
        assertEquals(
                methods.stream().map(PsiMethod::getName).toList(),
                lookup.statementQueries.stream().map(StatementQuery::statementId).toList());
        assertEquals(methodCount, lookup.namespaceQueries.size());
        assertTrue(lookup.namespaceQueries.stream()
                .allMatch("com.example.OrdinaryContract"::equals));

        List<MyBatisStatementResolution> cachedResolutions = ReadAction.compute(() -> methods.stream()
                .map(MyBatisStatementResolver::resolve)
                .toList());
        assertTrue(cachedResolutions.stream()
                .allMatch(MyBatisStatementResolution.NoMapperXml.class::isInstance));
        ReadAction.run(() -> {
            for (int index = 0; index < methodCount; index++) {
                assertSame(cachedResolutions.get(index),
                        MyBatisStatementResolver.resolve(methods.get(index)));
            }
        });
    }

    public void testStatementFirstLookupStaysWithinBudget() {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlTag statement = ((XmlFile) xmlFile).getRootTag().findFirstSubTag("select");
        assertNotNull(statement);
        PsiMethod method = configureMapper();

        CountingLookup matchingLookup = new CountingLookup(List.of(statement), true);
        MyBatisStatementResolution matching = ReadAction.compute(() ->
                MyBatisStatementResolver.resolveUncached(method, matchingLookup));
        assertTrue(matching instanceof MyBatisStatementResolution.UniqueMatch);
        assertEquals(
                List.of(new StatementQuery("com.example.UserMapper", "findById")),
                matchingLookup.statementQueries);
        assertEmpty(matchingLookup.namespaceQueries);

        CountingLookup missingStatementLookup = new CountingLookup(List.of(), true);
        MyBatisStatementResolution missingStatement = ReadAction.compute(() ->
                MyBatisStatementResolver.resolveUncached(method, missingStatementLookup));
        assertTrue(missingStatement instanceof MyBatisStatementResolution.StatementMissing);
        assertEquals(
                List.of(new StatementQuery("com.example.UserMapper", "findById")),
                missingStatementLookup.statementQueries);
        assertEquals(List.of("com.example.UserMapper"), missingStatementLookup.namespaceQueries);

        CountingLookup ordinaryInterfaceLookup = new CountingLookup(List.of(), false);
        MyBatisStatementResolution noMapperXml = ReadAction.compute(() ->
                MyBatisStatementResolver.resolveUncached(method, ordinaryInterfaceLookup));
        assertTrue(noMapperXml instanceof MyBatisStatementResolution.NoMapperXml);
        assertEquals(
                List.of(new StatementQuery("com.example.UserMapper", "findById")),
                ordinaryInterfaceLookup.statementQueries);
        assertEquals(List.of("com.example.UserMapper"), ordinaryInterfaceLookup.namespaceQueries);
    }

    public void testCachedResolutionIsSharedUntilIndexChanges() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper();

        MyBatisStatementResolution first = resolve(method);
        MyBatisStatementResolution second = resolve(method);

        assertTrue(first instanceof MyBatisStatementResolution.UniqueMatch);
        assertSame(first, second);
    }

    public void testXmlIdAndNamespaceChangesInvalidateCachedResolution() {
        PsiFile xmlFile = addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper();
        XmlTag mapperTag = ((XmlFile) xmlFile).getRootTag();
        XmlTag statement = mapperTag.findFirstSubTag("select");
        assertNotNull(statement);

        MyBatisStatementResolution matching = resolve(method);
        assertTrue(matching instanceof MyBatisStatementResolution.UniqueMatch);

        updateAttributeWithoutSaving(statement, "id", "findAll");
        MyBatisStatementResolution missing = resolve(method);
        assertTrue(missing instanceof MyBatisStatementResolution.StatementMissing);
        assertNotSame(matching, missing);

        updateAttributeWithoutSaving(mapperTag, "namespace", "com.example.OtherMapper");
        MyBatisStatementResolution noMapperXml = resolve(method);
        assertTrue(noMapperXml instanceof MyBatisStatementResolution.NoMapperXml);
        assertNotSame(missing, noMapperXml);
    }

    public void testMapperJavaStructureChangeInvalidatesCachedResolution() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper();
        MyBatisStatementResolution matching = resolve(method);
        assertTrue(matching instanceof MyBatisStatementResolution.UniqueMatch);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            method.getModifierList().add(JavaPsiFacade.getElementFactory(getProject())
                    .createAnnotationFromText("@java.lang.Deprecated", method));
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertNotNull(method.getAnnotation("java.lang.Deprecated"));
        MyBatisStatementResolution refreshed = resolve(method);
        assertTrue(refreshed instanceof MyBatisStatementResolution.UniqueMatch);
        assertNotSame(matching, refreshed);
    }

    public void testDumbToSmartTransitionInvalidatesCacheWithoutCachingTransientResult()
            throws Throwable {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiMethod method = configureMapper();
        MyBatisStatementResolution beforeDumbMode = resolve(method);
        assertTrue(beforeDumbMode instanceof MyBatisStatementResolution.UniqueMatch);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            MyBatisStatementResolution firstTransient = resolve(method);
            MyBatisStatementResolution secondTransient = resolve(method);
            assertTrue(firstTransient instanceof MyBatisStatementResolution.IndexNotReady);
            assertTrue(secondTransient instanceof MyBatisStatementResolution.IndexNotReady);
            assertNotSame(firstTransient, secondTransient);
        });

        MyBatisStatementResolution afterDumbMode = resolve(method);
        assertTrue(afterDumbMode instanceof MyBatisStatementResolution.UniqueMatch);
        assertNotSame(beforeDumbMode, afterDumbMode);
    }

    public void testDumbModePrecheckSkipsUnresolvedShortAnnotationWithoutIndexAccess()
            throws Throwable {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText(
                "UserMapper.java",
                """
                        package com.example;
                        public interface UserMapper {
                            @Select Object findById(long id);
                        }
                        """);
        PsiMethod method = findMethod(javaFile, "findById");
        CountingLookup lookup = new CountingLookup(List.of(), false);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            MyBatisStatementResolution resolution = ReadAction.compute(() ->
                    MyBatisStatementResolver.resolveUncached(method, lookup));
            assertTrue(resolution instanceof MyBatisStatementResolution.IndexNotReady);
        });

        assertEmpty(lookup.statementQueries);
        assertEmpty(lookup.namespaceQueries);
    }

    public void testDumbModeStillRejectsStructurallyUnsupportedSourcesBeforeIndexAccess()
            throws Throwable {
        PsiJavaFile interfaceFile = (PsiJavaFile) myFixture.configureByText(
                "UnsupportedMethods.java",
                """
                        package com.example;
                        public interface UnsupportedMethods {
                            static Object staticMethod() { return null; }
                            default Object defaultMethod() { return null; }
                            private Object bodyMethod() { return null; }
                            Object overloaded(long id);
                            Object overloaded(String id);
                        }
                        """);
        PsiJavaFile classFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/ConcreteMapper.java",
                """
                        package com.example;
                        public class ConcreteMapper {
                            public Object findById(long id) { return null; }
                        }
                        """);
        PsiJavaFile annotationFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/MapperContract.java",
                """
                        package com.example;
                        public @interface MapperContract {
                            String value();
                        }
                        """);
        CountingLookup lookup = new CountingLookup(List.of(), false);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> ReadAction.run(() -> {
            for (PsiMethod method : PsiTreeUtil.findChildrenOfType(interfaceFile, PsiMethod.class)) {
                assertTrue(method.getName() + " 在 Dumb Mode 下仍应按结构判为不支持",
                        MyBatisStatementResolver.resolveUncached(method, lookup)
                                instanceof MyBatisStatementResolution.UnsupportedSource);
            }
            assertTrue(MyBatisStatementResolver.resolveUncached(
                            findMethod(classFile, "findById"), lookup)
                    instanceof MyBatisStatementResolution.UnsupportedSource);
            assertTrue(MyBatisStatementResolver.resolveUncached(
                            findMethod(annotationFile, "value"), lookup)
                    instanceof MyBatisStatementResolution.UnsupportedSource);
        }));

        assertEmpty(lookup.statementQueries);
        assertEmpty(lookup.namespaceQueries);
    }

    public void testIndexNotReadyRaceDuringLookupIsConvertedToTypedResult() {
        PsiMethod method = configureMapper();

        MyBatisStatementResolution resolution = ReadAction.compute(() ->
                MyBatisStatementResolver.resolveUncached(method, IndexNotReadyLookup.INSTANCE));

        assertTrue(resolution instanceof MyBatisStatementResolution.IndexNotReady);
    }

    public void testSourceInvalidResultIsNotCached() {
        PsiMethod method = configureMapper();
        PsiFile javaFile = method.getContainingFile();
        WriteCommandAction.runWriteCommandAction(getProject(), javaFile::delete);

        MyBatisStatementResolution first = resolve(method);
        MyBatisStatementResolution second = resolve(method);

        assertTrue(first instanceof MyBatisStatementResolution.SourceInvalid);
        assertTrue(second instanceof MyBatisStatementResolution.SourceInvalid);
        assertNotSame(first, second);
    }

    private PsiFile addMapperXml(String xml) {
        return myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", xml);
    }

    private PsiMethod configureMapper() {
        PsiJavaFile javaFile = (PsiJavaFile) myFixture.configureByText(
                "UserMapper.java",
                """
                        package com.example;
                        public interface UserMapper {
                            Object findById(long id);
                        }
                        """);
        return findMethod(javaFile, "findById");
    }

    private PsiMethod findMethod(PsiJavaFile javaFile, String methodName) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private void updateAttributeWithoutSaving(XmlTag tag, String name, String value) {
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            tag.setAttribute(name, value);
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });
    }

    private void updateOrdinaryJavaBodyWithoutSaving(PsiJavaFile javaFile) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(javaFile);
        assertNotNull(document);
        String oldText = "return 1; /* 初始注释 */";
        int startOffset = document.getText().indexOf(oldText);
        assertTrue(startOffset >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.replaceString(
                    startOffset,
                    startOffset + oldText.length(),
                    "return 2; /* 修改后的注释 */");
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
    }

    private MyBatisStatementResolution resolve(PsiMethod method) {
        return ReadAction.compute(() -> MyBatisStatementResolver.resolve(method));
    }

    private static final class CountingLookup implements MyBatisStatementLookup {
        private final List<XmlTag> targets;
        private final boolean mapperXmlExists;
        private final List<StatementQuery> statementQueries = new ArrayList<>();
        private final List<String> namespaceQueries = new ArrayList<>();

        private CountingLookup(List<XmlTag> targets, boolean mapperXmlExists) {
            this.targets = targets;
            this.mapperXmlExists = mapperXmlExists;
        }

        @Override
        public @NotNull List<XmlTag> find(
                @NotNull Project project,
                @NotNull String namespace,
                @NotNull String statementId,
                @NotNull GlobalSearchScope scope) {
            statementQueries.add(new StatementQuery(namespace, statementId));
            return targets;
        }

        @Override
        public boolean hasMapperXml(
                @NotNull Project project,
                @NotNull String namespace,
                @NotNull GlobalSearchScope scope) {
            namespaceQueries.add(namespace);
            return mapperXmlExists;
        }
    }

    private record StatementQuery(@NotNull String namespace, @NotNull String statementId) {
    }

    private enum IndexNotReadyLookup implements MyBatisStatementLookup {
        INSTANCE;

        @Override
        public @NotNull List<XmlTag> find(
                @NotNull Project project,
                @NotNull String namespace,
                @NotNull String statementId,
                @NotNull GlobalSearchScope scope) {
            throw IndexNotReadyException.create();
        }

        @Override
        public boolean hasMapperXml(
                @NotNull Project project,
                @NotNull String namespace,
                @NotNull GlobalSearchScope scope) {
            throw IndexNotReadyException.create();
        }
    }
}
