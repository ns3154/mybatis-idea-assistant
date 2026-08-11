package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;

import java.util.Arrays;
import java.util.Collection;

public final class MyBatisXmlReferenceContributorTest extends BasePlatformTestCase {
    public void testNamespaceAndStatementIdResolveToExactJavaTargets() {
        PsiClass mapper = addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);

        configureMapperXml("""
                <mapper namespace="com.example.UserMa<caret>pper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiReference namespaceReference = referenceAtCaret();
        assertSame(mapper, namespaceReference.resolve());
        assertEquals("com.example.UserMapper", namespaceReference.getCanonicalText());

        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findBy<caret>Id">select 1</select>
                </mapper>
                """);
        PsiReference statementReference = referenceAtCaret();
        PsiElement statementTarget = statementReference.resolve();
        assertTrue(statementTarget instanceof PsiMethod);
        assertEquals("findById", ((PsiMethod) statementTarget).getName());
    }

    public void testStatementOverloadsRemainMultipleReferenceTargets() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                    Object findById(String id);
                }
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findBy<caret>Id">select 1</select>
                </mapper>
                """);

        ResolveResult[] results = multiResolve(referenceAtCaret());

        assertEquals(2, results.length);
        assertTrue(Arrays.stream(results).allMatch(result -> result.getElement() instanceof PsiMethod));
    }

    public void testStatementIdResolvesInheritedMapperMethod() {
        addMapperJava("""
                package com.example;
                public interface UserMapper extends BaseMapper<String> {
                }
                interface BaseMapper<T> {
                    T findById(long id);
                }
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findBy<caret>Id">select 1</select>
                </mapper>
                """);

        PsiElement target = referenceAtCaret().resolve();

        assertTrue(target instanceof PsiMethod);
        assertNotNull(((PsiMethod) target).getContainingClass());
        assertEquals("com.example.BaseMapper",
                ((PsiMethod) target).getContainingClass().getQualifiedName());
    }

    public void testIncludeResultMapAndExtendsResolveLocalDeclarations() {
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id, name</sql>
                    <resultMap id="baseMap" type="java.lang.Object"/>
                    <resultMap id="userMap" type="java.lang.Object" extends="base<caret>Map"/>
                    <select id="findAll" resultMap="userMap">
                        select <include refid="columns"/> from users
                    </select>
                </mapper>
                """);
        assertTag(referenceAtCaret().resolve(), "resultMap", "baseMap");

        moveCaretToAttributeValue("resultMap", "userMap");
        assertTag(referenceAtCaret().resolve(), "resultMap", "userMap");

        moveCaretTo("columns\"/>");
        assertTag(referenceAtCaret().resolve(), "sql", "columns");
    }

    public void testQualifiedReferencesResolveAcrossVisibleMapperXml() {
        myFixture.addFileToProject("src/main/resources/mappers/CommonMapper.xml", """
                <mapper namespace="com.example.CommonMapper">
                    <sql id="columns">id</sql>
                    <resultMap id="baseMap" type="java.lang.Object"/>
                </mapper>
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="java.lang.Object"
                               extends="com.example.CommonMapper.base<caret>Map"/>
                    <select id="findAll">
                        select <include refid="com.example.CommonMapper.columns"/> from users
                    </select>
                </mapper>
                """);

        assertTag(referenceAtCaret().resolve(), "resultMap", "baseMap");
        moveCaretTo("com.example.CommonMapper.columns");
        assertTag(referenceAtCaret().resolve(), "sql", "columns");
    }

    public void testCommaSeparatedResultMapsHaveIndependentReferenceRanges() {
        myFixture.addFileToProject("src/main/resources/mappers/CommonMapper.xml", """
                <mapper namespace="com.example.CommonMapper">
                    <resultMap id="sharedMap" type="java.lang.Object"/>
                </mapper>
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="localMap" type="java.lang.Object"/>
                    <select id="findAll"
                            resultMap="local<caret>Map, com.example.CommonMapper.sharedMap">
                        select 1
                    </select>
                </mapper>
                """);

        PsiReference localReference = referenceAtCaret();
        assertEquals("localMap", localReference.getCanonicalText());
        assertTag(localReference.resolve(), "resultMap", "localMap");

        moveCaretTo("sharedMap\"");
        PsiReference qualifiedReference = referenceAtCaret();
        assertEquals("com.example.CommonMapper.sharedMap",
                qualifiedReference.getCanonicalText());
        assertTag(qualifiedReference.resolve(), "resultMap", "sharedMap");

        XmlTag statement = xmlFile.getRootTag().findFirstSubTag("select");
        assertNotNull(statement);
        assertSize(2, Arrays.asList(
                statement.getAttribute("resultMap").getValueElement().getReferences()));
    }

    public void testReferencesSearchFindsNamespaceMethodAndXmlSymbolUsages() {
        PsiClass mapper = addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findAll();
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id</sql>
                    <resultMap id="userMap" type="java.lang.Object"/>
                    <select id="findAll" resultMap="userMap">
                        select <include refid="col<caret>umns"/> from users
                    </select>
                </mapper>
                """);
        XmlTag root = xmlFile.getRootTag();
        assertNotNull(root);
        XmlTag fragment = root.findFirstSubTag("sql");
        XmlTag resultMap = root.findFirstSubTag("resultMap");
        assertNotNull(fragment);
        assertNotNull(resultMap);
        PsiMethod method = mapper.findMethodsByName("findAll", false)[0];

        Collection<PsiReference> mapperReferences = ReadAction.compute(
                () -> ReferencesSearch.search(mapper).findAll());
        Collection<PsiReference> methodReferences = ReadAction.compute(
                () -> ReferencesSearch.search(method).findAll());
        Collection<PsiReference> fragmentReferences = ReadAction.compute(
                () -> ReferencesSearch.search(fragment).findAll());
        Collection<PsiReference> resultMapReferences = ReadAction.compute(
                () -> ReferencesSearch.search(resultMap).findAll());

        assertEquals(1, mapperReferences.size());
        assertEquals(1, methodReferences.size());
        assertEquals(1, fragmentReferences.size());
        assertEquals(1, resultMapReferences.size());
    }

    public void testUnsupportedDynamicPrefixedAndNestedAttributesHaveNoReference() {
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper" xmlns:x="urn:test">
                    <sql id="columns">id</sql>
                    <select x:id="findById">select 1</select>
                    <resultMap id="wrapper" type="java.lang.Object">
                        <select id="nes<caret>ted">select 1</select>
                    </resultMap>
                    <select id="findAll" resultMap="${dynamicMap}">
                        select <include refid="${dynamicSql}"/> from users
                    </select>
                </mapper>
                """);
        assertNull(myFixture.getReferenceAtCaretPosition());

        moveCaretTo("findById");
        assertNull(myFixture.getReferenceAtCaretPosition());
        moveCaretTo("${dynamicMap}");
        assertNull(myFixture.getReferenceAtCaretPosition());
        moveCaretTo("${dynamicSql}");
        assertNull(myFixture.getReferenceAtCaretPosition());
    }

    public void testDumbModeReturnsUnresolvedAndCancellationPropagates() throws Throwable {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById();
                }
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findBy<caret>Id">select 1</select>
                </mapper>
                """);
        PsiReference reference = referenceAtCaret();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertEmpty(
                multiResolve(reference)));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return multiResolve(reference);
                    },
                    indicator);
            fail("取消后的引用解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，引用解析不得吞掉。
        }
    }

    public void testUnsavedReferenceAndDeclarationChangesInvalidateImmediately() {
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <sql id="columns">id</sql>
                    <select id="findAll">select <include refid="col<caret>umns"/> from users</select>
                </mapper>
                """);
        PsiReference originalReference = referenceAtCaret();
        assertTag(originalReference.resolve(), "sql", "columns");
        XmlTag root = xmlFile.getRootTag();
        assertNotNull(root);
        XmlTag fragment = root.findFirstSubTag("sql");
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(fragment);
        assertNotNull(statement);
        XmlTag include = statement.findFirstSubTag("include");
        assertNotNull(include);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            fragment.setAttribute("id", "renamedColumns");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });
        assertNull(originalReference.resolve());

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            include.setAttribute("refid", "renamedColumns");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });
        PsiReference renamedReference = include.getAttribute("refid").getValueElement().getReferences()[0];
        assertTag(renamedReference.resolve(), "sql", "renamedColumns");

        WriteCommandAction.runWriteCommandAction(getProject(), xmlFile::delete);
        assertEmpty(multiResolve(renamedReference));
    }

    private PsiClass addMapperJava(String source) {
        return ((com.intellij.psi.PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/UserMapper.java",
                source)).getClasses()[0];
    }

    private XmlFile configureMapperXml(String source) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", source);
    }

    private PsiReference referenceAtCaret() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        assertNotNull(reference);
        return reference;
    }

    private ResolveResult[] multiResolve(PsiReference reference) {
        assertTrue(reference instanceof PsiPolyVariantReference);
        return ((PsiPolyVariantReference) reference).multiResolve(false);
    }

    private void moveCaretTo(String needle) {
        String text = myFixture.getFile().getText();
        int offset = text.indexOf(needle);
        assertTrue("测试文本中必须存在定位内容：" + needle, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset + Math.max(1, needle.length() / 2));
    }

    private void moveCaretToAttributeValue(String attributeName, String value) {
        String needle = attributeName + "=\"" + value + '"';
        String text = myFixture.getFile().getText();
        int offset = text.indexOf(needle);
        assertTrue("测试文本中必须存在属性：" + needle, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(
                offset + attributeName.length() + 2 + Math.max(1, value.length() / 2));
    }

    private void assertTag(PsiElement element, String tagName, String id) {
        assertTrue(element instanceof XmlTag);
        XmlTag tag = (XmlTag) element;
        assertEquals(tagName, tag.getName());
        assertEquals(id, MyBatisXmlModel.symbolId(tag));
    }
}
