package io.github.ns3154.mybatisassistant.reference;

import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.containers.MultiMap;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlLanguage;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlParserDefinition;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlPsiSupport;
import io.github.ns3154.mybatisassistant.refactoring.MyBatisOgnlBindingRenameProcessor;

import java.util.List;

public final class MyBatisOgnlReferenceTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                package org.apache.ibatis.annotations;
                public @interface Param { java.lang.String value(); }
                """);
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public class User {
                    public java.lang.String getName() { return ""; }
                    public int getAge() { return 0; }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("user") com.example.User user,
                                @Param("users")
                                java.util.List<com.example.User> users);
                }
                """);
        assertInstanceOf(
                LanguageParserDefinitions.INSTANCE.forLanguage(MyBatisOgnlLanguage.INSTANCE),
                MyBatisOgnlParserDefinition.class);
        List<com.intellij.util.KeyedLazyInstance<PsiReferenceContributor>> contributors =
                PsiReferenceContributor.EP_NAME.getExtensionList().stream()
                        .filter(extension -> "MyBatisOGNL".equals(extension.getKey()))
                        .filter(extension -> extension.getInstance()
                                instanceof MyBatisOgnlReferenceContributor)
                        .toList();
        assertSize(1, contributors);
        assertInstanceOf(
                contributors.getFirst().getInstance(),
                MyBatisOgnlReferenceContributor.class);
        ReferenceProvidersRegistry.getInstance().unloadProvidersFor(
                MyBatisOgnlLanguage.INSTANCE);
    }

    public void testRootPropertyAndMethodReferencesResolveFromRegisteredContributor() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="user.name != null and user.getAge() > 0">select 1</if>
                    </select>
                </mapper>
                """);
        XmlAttributeValue host = attributeValues(xml, "test").getFirst();
        PsiFile injected = injectedFile(host);
        assertNotNull(MyBatisOgnlPsiSupport.host(injected));
        assertNotNull(MyBatisOgnlPsiSupport.semanticModel(injected));
        assertFalse(MyBatisOgnlPsiSupport.semanticModel(injected).occurrences().isEmpty());

        MyBatisOgnlReference user = referenceAt(injected, "user", 0);
        MyBatisOgnlReference name = referenceAt(injected, "name", 0);
        MyBatisOgnlReference getAge = referenceAt(injected, "getAge", 0);

        assertInstanceOf(user.resolve(), PsiLiteralExpression.class);
        assertEquals("getName", assertInstanceOf(name.resolve(), PsiMethod.class).getName());
        assertEquals("getAge", assertInstanceOf(getAge.resolve(), PsiMethod.class).getName());
    }

    public void testBindAndForeachReferencesRespectLexicalDeclarations() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="user.name"/>
                        <foreach collection="users" item="entry">
                            <if test="pattern != null and entry.name != null">select 1</if>
                        </foreach>
                    </select>
                </mapper>
                """);
        PsiFile injected = injectedFile(attributeValues(xml, "test").getFirst());

        MyBatisOgnlReference pattern = referenceAt(injected, "pattern", 0);
        MyBatisOgnlReference entry = referenceAt(injected, "entry", 0);

        assertInstanceOf(pattern.resolve(), XmlAttributeValue.class);
        assertEquals("pattern", ((XmlAttributeValue) pattern.resolve()).getValue());
        assertTrue(entry.occurrence().toString(), entry.resolve() instanceof XmlAttributeValue);
        assertEquals("entry", ((XmlAttributeValue) entry.resolve()).getValue());
    }

    public void testDefinitelyMissingAndDynamicUnknownStayDistinct() {
        XmlFile missingXml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="user.missing != null">select 1</if>
                    </select>
                </mapper>
                """);
        MyBatisOgnlReference missing = referenceAt(
                injectedFile(attributeValues(missingXml, "test").getFirst()),
                "missing",
                0);
        assertNull(missing.resolve());
        assertTrue(missing.isDefinitelyMissing());

        myFixture.addFileToProject("src/main/java/com/example/MapMapper.java", """
                package com.example;
                public interface MapMapper {
                    Object find(java.util.Map<java.lang.String, java.lang.Object> values);
                }
                """);
        XmlFile dynamicXml = (XmlFile) myFixture.configureByText("MapMapper.xml", """
                <mapper namespace="com.example.MapMapper">
                    <select id="find"><if test="anything.value != null">x</if></select>
                </mapper>
                """);
        MyBatisOgnlReference dynamic = referenceAt(
                injectedFile(attributeValues(dynamicXml, "test").getFirst()),
                "anything",
                0);
        assertNull(dynamic.resolve());
        assertFalse(dynamic.isDefinitelyMissing());
    }

    public void testFindUsagesDiscoversInjectedPropertyReference() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="user.name != null">x</if></select>
                </mapper>
                """);
        PsiMethod getter = findUserMethod("getName");
        injectedFile(attributeValues(xml, "test").getFirst()).getReferences();

        List<PsiReference> usages = ReferencesSearch.search(getter).findAll().stream()
                .filter(MyBatisOgnlReference.class::isInstance)
                .toList();

        assertSize(1, usages);
        assertEquals("name", usages.getFirst().getCanonicalText());
    }

    public void testFindUsagesDiscoversBindAndForeachReferences() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="user.name"/>
                        <foreach collection="users" item="entry" index="position">
                            <if test="pattern != null and entry.name != null and position >= 0">
                                select 1
                            </if>
                        </foreach>
                    </select>
                </mapper>
                """);
        injectedFile(attributeValues(xml, "test").getFirst()).getReferences();

        assertOgnlUsage(attributeValue(xml, "bind", "name", "pattern"), "pattern");
        assertOgnlUsage(attributeValue(xml, "foreach", "item", "entry"), "entry");
        assertOgnlUsage(attributeValue(xml, "foreach", "index", "position"), "position");
    }

    public void testReferenceRenameRewritesOnlyInjectedNameRange() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="user.name != null">x</if></select>
                </mapper>
                """);
        XmlAttributeValue host = attributeValues(xml, "test").getFirst();
        PsiFile injected = injectedFile(host);
        MyBatisOgnlReference name = referenceAt(injected, "name", 0);

        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    name.handleElementRename("getFullName");
                });

        assertEquals("user.fullName != null", host.getValue());
    }

    public void testXmlEntityBeforeReferenceKeepsExactResolveAndRenameRange() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="1 &lt; user.name.length()">x</if></select>
                </mapper>
                """);
        XmlAttributeValue host = attributeValues(xml, "test").getFirst();
        PsiFile injected = injectedFile(host);
        MyBatisOgnlReference name = referenceAt(injected, "name", 0);

        assertEquals("getName", assertInstanceOf(name.resolve(), PsiMethod.class).getName());
        WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    name.handleElementRename("getFullName");
                });

        assertEquals("1 &lt; user.fullName.length()", host.getValue());
    }

    public void testBindDeclarationRenameUpdatesAllInjectedReferences() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="user.name"/>
                        <if test="pattern != null">x</if>
                        <when test="pattern == 'x'">y</when>
                    </select>
                </mapper>
                """);
        XmlAttributeValue declaration = attributeValue(xml, "bind", "name", "pattern");

        myFixture.renameElement(declaration, "term");

        assertFalse(xml.getText().contains("pattern"));
        assertTrue(xml.getText().contains("name=\"term\""));
        assertTrue(xml.getText().contains("test=\"term != null\""));
        assertTrue(xml.getText().contains("test=\"term == 'x'\""));
    }

    public void testForeachBindingRenameUpdatesOnlyItsLexicalReferences() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <foreach collection="users" item="entry" index="position">
                            <if test="entry.name != null and position >= 0">x</if>
                        </foreach>
                    </select>
                </mapper>
                """);
        XmlAttributeValue item = attributeValue(xml, "foreach", "item", "entry");
        XmlAttributeValue index = attributeValue(xml, "foreach", "index", "position");

        myFixture.renameElement(item, "candidate");
        myFixture.renameElement(index, "offset");

        assertFalse(xml.getText().contains("entry"));
        assertFalse(xml.getText().contains("position"));
        assertTrue(xml.getText().contains("item=\"candidate\""));
        assertTrue(xml.getText().contains("index=\"offset\""));
        assertTrue(xml.getText().contains("candidate.name != null and offset >= 0"));
    }

    public void testBindingRenameRejectsInvalidAndCollidingNames() {
        XmlFile xml = configureMapper("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="user.name"/>
                        <foreach collection="users" item="entry">
                            <if test="pattern != null and entry.name != null">x</if>
                        </foreach>
                    </select>
                </mapper>
                """);
        XmlAttributeValue pattern = attributeValue(xml, "bind", "name", "pattern");
        MyBatisOgnlBindingRenameProcessor processor =
                new MyBatisOgnlBindingRenameProcessor();
        assertTrue(processor.canProcessElement(pattern));

        MultiMap<PsiElement, String> invalid = new MultiMap<>();
        processor.findExistingNameConflicts(pattern, "not-valid", invalid);
        assertFalse(invalid.isEmpty());

        MultiMap<PsiElement, String> collision = new MultiMap<>();
        processor.findExistingNameConflicts(pattern, "entry", collision);
        assertFalse(collision.isEmpty());
        assertTrue(collision.values().stream().anyMatch(
                message -> message.contains("已存在 OGNL 绑定：entry")));
    }

    private XmlFile configureMapper(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private PsiMethod findUserMethod(String name) {
        return JavaPsiFacade.getInstance(getProject()).findClass(
                "com.example.User",
                GlobalSearchScope.projectScope(getProject()))
                .findMethodsByName(name, false)[0];
    }

    private static List<XmlAttributeValue> attributeValues(XmlFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(file, XmlAttribute.class).stream()
                .filter(attribute -> name.equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static XmlAttributeValue attributeValue(
            XmlFile file,
            String tagName,
            String attributeName,
            String value) {
        return PsiTreeUtil.findChildrenOfType(file, XmlAttribute.class).stream()
                .filter(attribute -> attributeName.equals(attribute.getName()))
                .filter(attribute -> attribute.getParent() instanceof com.intellij.psi.xml.XmlTag tag
                        && tagName.equals(tag.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .filter(attributeValue -> value.equals(attributeValue.getValue()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertOgnlUsage(
            XmlAttributeValue declaration,
            String expectedText) {
        List<PsiReference> usages = ReferencesSearch.search(declaration).findAll().stream()
                .filter(MyBatisOgnlReference.class::isInstance)
                .toList();
        assertSize(1, usages);
        assertEquals(expectedText, usages.getFirst().getCanonicalText());
        assertTrue(usages.getFirst().isReferenceTo(declaration));
    }

    private PsiFile injectedFile(XmlAttributeValue host) {
        List<Pair<PsiElement, TextRange>> files = InjectedLanguageManager
                .getInstance(getProject())
                .getInjectedPsiFiles(host);
        assertNotNull(files);
        assertSize(1, files);
        PsiElement element = files.getFirst().getFirst();
        return element instanceof PsiFile file ? file : element.getContainingFile();
    }

    private static MyBatisOgnlReference referenceAt(
            PsiFile file,
            String text,
            int occurrence) {
        int offset = -1;
        for (int count = 0; count <= occurrence; count++) {
            offset = file.getText().indexOf(text, offset + 1);
        }
        assertTrue("找不到引用文本：" + text, offset >= 0);
        PsiReference reference = file.findReferenceAt(offset);
        PsiElement leaf = file.findElementAt(offset);
        StringBuilder diagnostics = new StringBuilder();
        for (PsiElement current = leaf; current != null && current != file;
                current = current.getParent()) {
            diagnostics.append(current.getClass().getSimpleName())
                    .append(' ')
                    .append(current.getLanguage().getID())
                    .append(' ')
                    .append(current.getTextRange())
                    .append(" refs=")
                    .append(current.getReferences().length)
                    .append('\n');
        }
        assertTrue(diagnostics.toString(), reference instanceof MyBatisOgnlReference);
        return (MyBatisOgnlReference) reference;
    }
}
