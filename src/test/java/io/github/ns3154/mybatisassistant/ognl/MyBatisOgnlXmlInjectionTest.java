package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class MyBatisOgnlXmlInjectionTest extends BasePlatformTestCase {
    public void testParserDefinitionIsRegisteredByPluginXml() {
        ParserDefinition definition = LanguageParserDefinitions.INSTANCE
                .forLanguage(MyBatisOgnlLanguage.INSTANCE);

        assertNotNull(definition);
        assertInstanceOf(definition, MyBatisOgnlParserDefinition.class);
        assertEquals(MyBatisOgnlParserDefinition.FILE, definition.getFileNodeType());
    }

    public void testSyntaxHighlighterFactoryIsRegisteredByPluginXml() {
        SyntaxHighlighter highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
                MyBatisOgnlLanguage.INSTANCE,
                getProject(),
                null);

        assertInstanceOf(highlighter, MyBatisOgnlSyntaxHighlighter.class);
        assertContainsElements(
                List.of(highlighter.getTokenHighlights(
                        MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.AND))),
                DefaultLanguageHighlighterColors.KEYWORD);
        assertContainsElements(
                List.of(highlighter.getTokenHighlights(
                        MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.STRING))),
                DefaultLanguageHighlighterColors.STRING);
    }

    public void testInjectsConditionAndBindExpressionsWithCompositePsi() {
        XmlFile file = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <bind name="pattern" value="'%' + _parameter['name'] + '%'"/>
                        <if test="name != null and !name.isEmpty()">select 1</if>
                    </select>
                </mapper>
                """);

        PsiFile bind = injectedFile(findAttributeValue(file, "value"));
        PsiFile condition = injectedFile(findAttributeValue(file, "test"));

        assertInstanceOf(bind, MyBatisOgnlFile.class);
        assertSame(MyBatisOgnlLanguage.INSTANCE, bind.getLanguage());
        assertHasElementType(bind, MyBatisOgnlTypes.BINARY);
        assertHasElementType(bind, MyBatisOgnlTypes.INDEX);
        assertHasElementType(condition, MyBatisOgnlTypes.BINARY);
        assertHasElementType(condition, MyBatisOgnlTypes.UNARY);
        assertHasElementType(condition, MyBatisOgnlTypes.METHOD_CALL);
    }

    public void testXmlEntityIsDecodedBeforeOgnlParsing() {
        XmlFile file = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="age &lt; 18">select 1</if>
                    </select>
                </mapper>
                """);

        PsiFile injected = injectedFile(findAttributeValue(file, "test"));

        assertEquals("age &lt; 18", injected.getText());
        assertHasElementType(injected, MyBatisOgnlTypes.BINARY);
        assertEmpty(PsiTreeUtil.findChildrenOfType(injected, PsiErrorElement.class));
    }

    public void testIncompleteExpressionProducesRecoverableErrorPsi() {
        XmlFile file = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="name !=">select 1</if>
                    </select>
                </mapper>
                """);

        PsiFile injected = injectedFile(findAttributeValue(file, "test"));

        assertHasElementType(injected, MyBatisOgnlTypes.BINARY);
        assertFalse(PsiTreeUtil.findChildrenOfType(
                injected,
                PsiErrorElement.class).isEmpty());
    }

    public void testDoesNotInjectOutsideSupportedMyBatisAttributes() {
        XmlFile file = configureMapperXml("""
                <mapper xmlns:x="urn:test" namespace="com.example.UserMapper">
                    <select id="find" test="name != null">
                        <x:if test="name != null">select 1</x:if>
                        <if x:test="name != null">select 2</if>
                        <bind name="pattern" value="name"/>
                    </select>
                </mapper>
                """);

        List<XmlAttributeValue> values = PsiTreeUtil.findChildrenOfType(
                file,
                XmlAttributeValue.class).stream()
                .filter(value -> value.getValue().contains("name"))
                .toList();

        long injectedCount = values.stream().filter(this::hasInjection).count();
        assertEquals(1L, injectedCount);
        assertEquals("name", injectedFile(values.stream()
                .filter(this::hasInjection)
                .findFirst()
                .orElseThrow()).getText());

        XmlFile outsideMapper = (XmlFile) myFixture.configureByText(
                "ordinary.xml",
                "<root><if test=\"name != null\">x</if></root>");
        assertFalse(hasInjection(findAttributeValue(outsideMapper, "test")));
    }

    public void testUnsavedAttributeEditRefreshesInjectedPsi() {
        XmlFile file = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="name != null">select 1</if>
                    </select>
                </mapper>
                """);
        XmlAttributeValue value = findAttributeValue(file, "test");
        PsiFile before = injectedFile(value);
        assertEquals("name != null", before.getText());

        var document = PsiDocumentManager.getInstance(getProject())
                .getDocument(file);
        assertNotNull(document);
        TextRange valueRange = value.getValueTextRange();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.replaceString(
                valueRange.getStartOffset(),
                valueRange.getEndOffset(),
                "age >= 18"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        XmlAttributeValue updated = findAttributeValue(file, "test");
        PsiFile after = injectedFile(updated);
        assertEquals("age >= 18", after.getText());
        assertHasElementType(after, MyBatisOgnlTypes.BINARY);
    }

    public void testDeepPropertyChainBuildsPsiWithoutStackOverflow() {
        String expression = "root" + ".child".repeat(2_000);
        XmlFile file = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        <if test="%s">select 1</if>
                    </select>
                </mapper>
                """.formatted(expression));

        PsiFile injected = injectedFile(findAttributeValue(file, "test"));

        assertEquals(expression, injected.getText());
        assertEquals(2_000, countElementType(injected, MyBatisOgnlTypes.PROPERTY));
    }

    private XmlFile configureMapperXml(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private static XmlAttributeValue findAttributeValue(XmlFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(file, XmlAttribute.class).stream()
                .filter(attribute -> name.equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
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

    private boolean hasInjection(XmlAttributeValue value) {
        return InjectedLanguageManager.getInstance(getProject()).hasInjections(value);
    }

    private static void assertHasElementType(PsiFile file, IElementType type) {
        assertTrue("缺少 PSI 元素类型：" + type, countElementType(file, type) > 0);
    }

    private static int countElementType(PsiFile file, IElementType type) {
        int count = 0;
        Deque<PsiElement> pending = new ArrayDeque<>();
        pending.push(file);
        while (!pending.isEmpty()) {
            PsiElement element = pending.pop();
            if (element.getNode() != null
                    && type.equals(element.getNode().getElementType())) {
                count++;
            }
            for (PsiElement child = element.getLastChild();
                    child != null;
                    child = child.getPrevSibling()) {
                pending.push(child);
            }
        }
        return count;
    }
}
