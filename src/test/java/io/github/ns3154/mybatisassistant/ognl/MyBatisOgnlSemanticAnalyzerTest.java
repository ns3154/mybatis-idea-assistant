package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyAccess;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolver;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;

import java.util.List;

public final class MyBatisOgnlSemanticAnalyzerTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                package org.apache.ibatis.annotations;
                public @interface Param { String value(); }
                """);
        addDomainModel();
    }

    public void testDirectParameterPropertiesAndGenericIndexAreExact() {
        addMapper("""
                package com.example;
                public interface UserMapper {
                    Object find(com.example.Query query);
                }
                """);
        PsiClass queryClass = JavaPsiFacade.getInstance(getProject()).findClass(
                "com.example.Query",
                GlobalSearchScope.projectScope(getProject()));
        assertNotNull(queryClass);
        assertFalse(MyBatisJavaPropertyResolver.resolve(
                JavaPsiFacade.getElementFactory(getProject()).createType(queryClass),
                "name",
                MyBatisJavaPropertyAccess.READ).targets().isEmpty());
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="name != null and users[0].age > 0">select 1</if>
                    </select>
                </mapper>
                """);
        List<PsiMethod> methods = MyBatisMapperMethodResolver.find(
                file,
                "com.example.UserMapper",
                "find");
        assertSize(1, methods);
        PsiClass mapperClass = methods.getFirst().getContainingClass();
        assertNotNull(mapperClass);
        MyBatisParameterContextResolution context = MyBatisParameterContextResolver.resolve(
                mapperClass,
                methods.getFirst());
        assertInstanceOf(context, MyBatisParameterContextResolution.Found.class);
        PsiType contextType = ((MyBatisParameterContextResolution.Found) context)
                .context().directType();
        assertNotNull(contextType);
        assertEquals("com.example.Query", contextType.getCanonicalText());
        assertFalse(MyBatisJavaPropertyResolver.resolve(
                contextType,
                "name",
                MyBatisJavaPropertyAccess.READ).targets().isEmpty());

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertStatus(model, "name", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.FOUND);
        assertType(model, "name", MyBatisOgnlSymbolKind.ROOT, "java.lang.String");
        assertStatus(model, "users", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.FOUND);
        assertStatus(model, "age", MyBatisOgnlSymbolKind.PROPERTY,
                MyBatisOgnlSemanticStatus.FOUND);
        assertType(model, "age", MyBatisOgnlSymbolKind.PROPERTY, "int");
        assertEquals(MyBatisOgnlSemanticStatus.FOUND, model.rootResult().status());
        assertEquals("boolean", model.rootResult().types().getFirst().getCanonicalText());
    }

    public void testNamedParametersAndBuiltInsResolveConservatively() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("query") com.example.Query query,
                                @Param("limit") int limit);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="query.name != null and limit > 0
                                and _databaseId != null and _parameter.anything != null">
                            select 1
                        </if>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertTargetType(model, "query", MyBatisOgnlSymbolKind.ROOT,
                PsiLiteralExpression.class);
        assertTargetType(model, "limit", MyBatisOgnlSymbolKind.ROOT,
                PsiLiteralExpression.class);
        assertType(model, "_databaseId", MyBatisOgnlSymbolKind.ROOT, "java.lang.String");
        assertStatus(model, "anything", MyBatisOgnlSymbolKind.PROPERTY,
                MyBatisOgnlSemanticStatus.UNKNOWN);
    }

    public void testBindIsVisibleOnlyAfterItsDeclaration() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("query") com.example.Query query);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="pattern != null">select 1</if>
                        <bind name="pattern" value="'%' + query.name + '%'"/>
                        <if test="pattern != null">select 2</if>
                    </select>
                </mapper>
                """);
        List<XmlAttributeValue> tests = attributeValues(file, "test");

        MyBatisOgnlSemanticModel before = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(0));
        MyBatisOgnlSemanticModel after = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(1));

        assertStatus(before, "pattern", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.DEFINITE_MISSING);
        assertStatus(after, "pattern", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.FOUND);
        assertType(after, "pattern", MyBatisOgnlSymbolKind.ROOT, "java.lang.String");
        assertTargetType(after, "pattern", MyBatisOgnlSymbolKind.ROOT,
                XmlAttributeValue.class);
    }

    public void testForeachBindingsAreTypedAndDoNotLeak() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("users")
                                java.util.List<com.example.User> users);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <foreach collection="users" item="user" index="i">
                            <if test="user.age > i">select 1</if>
                        </foreach>
                        <if test="user != null">select 2</if>
                    </select>
                </mapper>
                """);
        List<XmlAttributeValue> tests = attributeValues(file, "test");

        MyBatisOgnlSemanticModel inside = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(0));
        MyBatisOgnlSemanticModel outside = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(1));

        assertType(inside, "user", MyBatisOgnlSymbolKind.ROOT, "com.example.User");
        assertType(inside, "i", MyBatisOgnlSymbolKind.ROOT, "int");
        assertStatus(inside, "age", MyBatisOgnlSymbolKind.PROPERTY,
                MyBatisOgnlSemanticStatus.FOUND);
        assertStatus(outside, "user", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.DEFINITE_MISSING);
    }

    public void testNestedForeachShadowsOuterItem() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("groups")
                                java.util.List<com.example.Group> groups);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <foreach collection="groups" item="entry">
                            <foreach collection="entry.users" item="entry">
                                <if test="entry.age > 0">select 1</if>
                            </foreach>
                        </foreach>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertType(model, "entry", MyBatisOgnlSymbolKind.ROOT, "com.example.User");
        assertType(model, "age", MyBatisOgnlSymbolKind.PROPERTY, "int");
    }

    public void testMapAndRawCollectionRemainUnknown() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("values")
                                java.util.Map<java.lang.String, com.example.User> values,
                                @Param("raw") java.util.List raw);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="values['key'].age != null">select 1</if>
                        <foreach collection="raw" item="entry">
                            <if test="entry.name != null">select 2</if>
                        </foreach>
                    </select>
                </mapper>
                """);
        List<XmlAttributeValue> tests = attributeValues(file, "test");

        MyBatisOgnlSemanticModel map = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(0));
        MyBatisOgnlSemanticModel raw = MyBatisOgnlSemanticAnalyzer.analyze(tests.get(1));

        assertStatus(map, "age", MyBatisOgnlSymbolKind.PROPERTY,
                MyBatisOgnlSemanticStatus.UNKNOWN);
        assertStatus(raw, "entry", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.UNKNOWN);
        assertStatus(raw, "name", MyBatisOgnlSymbolKind.PROPERTY,
                MyBatisOgnlSemanticStatus.UNKNOWN);
    }

    public void testMethodOverloadIsUnknownButUniqueMethodAndStaticFieldResolve() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("query") com.example.Query query);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="query.isReady() and query.lookup(1) != null
                                and @com.example.Constants@MAX_VALUE > 0">
                            select 1
                        </if>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertStatus(model, "isReady", MyBatisOgnlSymbolKind.METHOD,
                MyBatisOgnlSemanticStatus.FOUND);
        assertStatus(model, "lookup", MyBatisOgnlSymbolKind.METHOD,
                MyBatisOgnlSemanticStatus.UNKNOWN);
        assertStatus(model, "com.example.Constants", MyBatisOgnlSymbolKind.STATIC_CLASS,
                MyBatisOgnlSemanticStatus.FOUND);
        assertType(model, "MAX_VALUE", MyBatisOgnlSymbolKind.STATIC_MEMBER, "int");
    }

    public void testCharacterStringAndNullKeepDistinctTypes() {
        addMapper("""
                package com.example;
                public interface UserMapper { Object find(com.example.Query query); }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="letter" value="'A'"/>
                        <bind name="text" value="&quot;A&quot;"/>
                        <if test="letter != null and text != null">select 1</if>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertType(model, "letter", MyBatisOgnlSymbolKind.ROOT, "char");
        assertType(model, "text", MyBatisOgnlSymbolKind.ROOT, "java.lang.String");
    }

    public void testDumbModeAndCancellationAreTyped() {
        addMapper("""
                package com.example;
                public interface UserMapper { Object find(com.example.Query query); }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="name != null">select 1</if></select>
                </mapper>
                """);
        XmlAttributeValue value = attributeValues(file, "test").getFirst();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertEquals(
                MyBatisOgnlSemanticStatus.INDEX_NOT_READY,
                MyBatisOgnlSemanticAnalyzer.analyze(value).rootResult().status()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisOgnlSemanticAnalyzer.analyze(value);
                }, indicator));
    }

    public void testInstanceOfTypeLiteralResolvesWithoutFalseMissingRoots() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("query") com.example.Query query);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="query instanceof com.example.Query">x</if>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertStatus(model, "com.example.Query", MyBatisOgnlSymbolKind.STATIC_CLASS,
                MyBatisOgnlSemanticStatus.FOUND);
        assertEquals(MyBatisOgnlSemanticStatus.FOUND, model.rootResult().status());
        assertFalse(model.occurrences().stream().anyMatch(occurrence ->
                "com".equals(occurrence.name())
                        || "example".equals(occurrence.name())));
    }

    private void addDomainModel() {
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public int getAge() { return 0; }
                    public java.lang.String getName() { return ""; }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/Group.java", """
                package com.example;
                public class Group {
                    public java.util.List<com.example.User> getUsers() { return null; }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/Query.java", """
                package com.example;
                public class Query {
                    public java.lang.String getName() { return ""; }
                    public java.util.List<com.example.User> getUsers() { return null; }
                    public boolean isReady() { return true; }
                    public java.lang.String lookup(int value) { return ""; }
                    public java.lang.String lookup(long value) { return ""; }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/Constants.java", """
                package com.example;
                public final class Constants {
                    public static final int MAX_VALUE = 100;
                    private Constants() { }
                }
                """);
    }

    private void addMapper(String text) {
        myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", text);
    }

    private XmlFile configureMapper(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private static MyBatisOgnlSemanticModel analyze(XmlFile file, String attribute) {
        return MyBatisOgnlSemanticAnalyzer.analyze(
                attributeValues(file, attribute).getFirst());
    }

    private static List<XmlAttributeValue> attributeValues(XmlFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(file, XmlAttribute.class).stream()
                .filter(attribute -> name.equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static MyBatisOgnlOccurrence occurrence(
            MyBatisOgnlSemanticModel model,
            String name,
            MyBatisOgnlSymbolKind kind) {
        return model.occurrences().stream()
                .filter(candidate -> name.equals(candidate.name()) && kind == candidate.kind())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少 occurrence：" + kind + ' ' + name + "，实际="
                                + model.occurrences()));
    }

    private static void assertStatus(
            MyBatisOgnlSemanticModel model,
            String name,
            MyBatisOgnlSymbolKind kind,
            MyBatisOgnlSemanticStatus status) {
        MyBatisOgnlOccurrence occurrence = occurrence(model, name, kind);
        assertEquals(occurrence.toString(), status, occurrence.result().status());
    }

    private static void assertType(
            MyBatisOgnlSemanticModel model,
            String name,
            MyBatisOgnlSymbolKind kind,
            String canonicalType) {
        List<String> types = occurrence(model, name, kind).result().types().stream()
                .map(PsiType::getCanonicalText)
                .toList();
        assertContainsElements(
                occurrence(model, name, kind).toString(),
                types,
                canonicalType);
    }

    private static void assertTargetType(
            MyBatisOgnlSemanticModel model,
            String name,
            MyBatisOgnlSymbolKind kind,
            Class<? extends PsiElement> targetType) {
        assertTrue(occurrence(model, name, kind).result().targets().stream()
                .anyMatch(targetType::isInstance));
    }

    public void testCacheIsSharedAndUnsavedXmlEditInvalidatesIt() {
        addMapper("""
                package com.example;
                public interface UserMapper { Object find(com.example.Query query); }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="name != null">select 1</if></select>
                </mapper>
                """);
        XmlAttributeValue source = attributeValues(file, "test").getFirst();

        MyBatisOgnlSemanticModel first = MyBatisOgnlSemanticAnalyzer.analyze(source);
        assertSame(first, MyBatisOgnlSemanticAnalyzer.analyze(source));

        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        int offset = document.getText().indexOf("name != null");
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> document.replaceString(offset, offset + "name".length(), "missing"));
        PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        XmlAttributeValue changed = attributeValues(file, "test").getFirst();

        MyBatisOgnlSemanticModel second = MyBatisOgnlSemanticAnalyzer.analyze(changed);
        assertNotSame(first, second);
        assertStatus(second, "missing", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.DEFINITE_MISSING);
        assertSame(second, MyBatisOgnlSemanticAnalyzer.analyze(changed));
    }

    public void testJavaStructureChangeInvalidatesCachedSemanticModel() {
        addMapper("""
                package com.example;
                public interface UserMapper { Object find(com.example.Query query); }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="name != null">select 1</if></select>
                </mapper>
                """);
        XmlAttributeValue source = attributeValues(file, "test").getFirst();
        MyBatisOgnlSemanticModel first = MyBatisOgnlSemanticAnalyzer.analyze(source);
        PsiClass query = JavaPsiFacade.getInstance(getProject()).findClass(
                "com.example.Query",
                GlobalSearchScope.projectScope(getProject()));
        assertNotNull(query);
        PsiMethod getter = query.findMethodsByName("getName", false)[0];

        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    getter.setName("getDisplayName");
                });
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        MyBatisOgnlSemanticModel second = MyBatisOgnlSemanticAnalyzer.analyze(source);
        assertNotSame(first, second);
        assertStatus(second, "name", MyBatisOgnlSymbolKind.ROOT,
                MyBatisOgnlSemanticStatus.DEFINITE_MISSING);
    }

    public void testDumbModeLifecycleResultsAreNeverCached() {
        addMapper("""
                package com.example;
                public interface UserMapper { Object find(com.example.Query query); }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="name != null">select 1</if></select>
                </mapper>
                """);
        XmlAttributeValue source = attributeValues(file, "test").getFirst();
        MyBatisOgnlSemanticModel before = MyBatisOgnlSemanticAnalyzer.analyze(source);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            MyBatisOgnlSemanticModel first = MyBatisOgnlSemanticAnalyzer.analyze(source);
            MyBatisOgnlSemanticModel second = MyBatisOgnlSemanticAnalyzer.analyze(source);
            assertEquals(MyBatisOgnlSemanticStatus.INDEX_NOT_READY,
                    first.rootResult().status());
            assertEquals(MyBatisOgnlSemanticStatus.INDEX_NOT_READY,
                    second.rootResult().status());
            assertNotSame(first, second);
        });

        MyBatisOgnlSemanticModel after = MyBatisOgnlSemanticAnalyzer.analyze(source);
        assertEquals(MyBatisOgnlSemanticStatus.FOUND, after.rootResult().status());
        assertNotSame(before, after);
        assertSame(after, MyBatisOgnlSemanticAnalyzer.analyze(source));
    }

    public void testCollectionAndArrayPseudoPropertiesRemainValidOgnl() {
        addMapper("""
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("users") java.util.List<com.example.User> users,
                                @Param("ids") long[] ids);
                }
                """);
        XmlFile file = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="users.size > 0 and !users.isEmpty and ids.length > 0">x</if>
                    </select>
                </mapper>
                """);

        MyBatisOgnlSemanticModel model = analyze(file, "test");

        assertType(model, "size", MyBatisOgnlSymbolKind.PROPERTY, "int");
        assertType(model, "isEmpty", MyBatisOgnlSymbolKind.PROPERTY, "boolean");
        assertType(model, "length", MyBatisOgnlSymbolKind.PROPERTY, "int");
        assertEquals(MyBatisOgnlSemanticStatus.FOUND, model.rootResult().status());
    }
}
