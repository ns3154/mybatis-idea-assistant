package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisXmlLineMarkerProviderTest extends BasePlatformTestCase {
    public void testRegisteredProviderProducesGuttersOnlyForDirectStatements() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findOne();
                    int insertOne();
                    int updateOne();
                    int deleteOne();
                    Object fragment();
                    Object nested();
                    Object named();
                }
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findOne">select 1</select>
                    <insert id="insertOne">insert into sample values (1)</insert>
                    <update id="updateOne">update sample set id = 1</update>
                    <delete id="deleteOne">delete from sample</delete>
                    <sql id="fragment">id</sql>
                    <resultMap id="wrapper" type="java.lang.Object">
                        <select id="nested">select 1</select>
                    </resultMap>
                    <select name="named">select 1</select>
                </mapper>
                """);

        myFixture.doHighlighting();
        List<GutterMark> gutters = mapperGutters();

        assertSize(4, gutters);
        for (GutterMark gutter : gutters) {
            RelatedItemLineMarkerInfo<?> marker = markerInfo(gutter);
            assertTrue(marker.getElement() instanceof XmlToken);
            assertEquals(XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN,
                    ((XmlToken) marker.getElement()).getTokenType());
        }
    }

    public void testRegisteredGutterNavigatesToUniqueMapperMethod() throws Throwable {
        PsiJavaFile javaFile = addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        PsiMethod method = findMethod(javaFile, "findById");
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        myFixture.doHighlighting();
        List<GutterMark> gutters = mapperGutters();
        assertSize(1, gutters);
        RelatedItemLineMarkerInfo<?> marker = markerInfo(gutters.getFirst());
        navigate(marker);
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

        FileEditorManager editorManager = FileEditorManager.getInstance(getProject());
        assertContainsElements(List.of(editorManager.getSelectedFiles()), javaFile.getVirtualFile());
        assertNotNull(editorManager.getSelectedTextEditor());
        assertTrue(method.getTextRange().contains(
                editorManager.getSelectedTextEditor().getCaretModel().getOffset()));
    }

    public void testOverloadsRemainAsMultipleNavigationCandidates() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                    Object findById(String id);
                }
                """);
        configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        myFixture.doHighlighting();
        List<GutterMark> gutters = mapperGutters();
        assertSize(1, gutters);
        RelatedItemLineMarkerInfo<?> marker = markerInfo(gutters.getFirst());
        List<PsiMethod> methods = marker.createGotoRelatedItems().stream()
                .map(item -> item.getElement())
                .filter(PsiMethod.class::isInstance)
                .map(PsiMethod.class::cast)
                .toList();

        assertSize(2, methods);
        assertTrue(methods.get(0).getTextOffset() < methods.get(1).getTextOffset());
        assertEquals("long", methods.get(0).getParameterList()
                .getParameters()[0].getType().getCanonicalText());
        assertEquals("String", methods.get(1).getParameterList()
                .getParameters()[0].getType().getCanonicalText());
    }

    public void testMissingNamespaceClassHasNoTarget() {
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.MissingMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken)));
        myFixture.doHighlighting();
        assertEmpty(mapperGutters());
    }

    public void testMissingStatementMethodHasNoTarget() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findAll();
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken)));
        myFixture.doHighlighting();
        assertEmpty(mapperGutters());
    }

    public void testSameSimpleClassNameMatchesOnlyExactNamespace() {
        myFixture.addFileToProject("src/main/java/com/first/UserMapper.java", """
                package com.first;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        myFixture.addFileToProject("src/main/java/com/second/UserMapper.java", """
                package com.second;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.second.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        List<PsiMethod> targets = ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken));

        assertSize(1, targets);
        assertNotNull(targets.getFirst().getContainingClass());
        assertEquals("com.second.UserMapper",
                targets.getFirst().getContainingClass().getQualifiedName());
    }

    public void testOrdinaryClassDoesNotMatchMapperNamespace() {
        addMapperJava("""
                package com.example;
                public class UserMapper {
                    public Object findById(long id) {
                        return null;
                    }
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken)));
    }

    public void testInheritedMethodMatchesStatement() {
        addMapperJava("""
                package com.example;
                public interface UserMapper extends BaseMapper {
                }
                interface BaseMapper {
                    Object findById(long id);
                }
                """);
        PsiClass mapper = ReadAction.compute(() -> JavaPsiFacade.getInstance(getProject())
                .findClass("com.example.UserMapper", GlobalSearchScope.projectScope(getProject())));
        assertNotNull(mapper);
        PsiClass baseMapper = ReadAction.compute(
                () -> mapper.getExtendsListTypes()[0].resolve());
        assertNotNull(baseMapper);
        assertEquals("com.example.BaseMapper", baseMapper.getQualifiedName());
        assertSize(1, ReadAction.compute(() -> mapper.findMethodsByName("findById", true)));
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        List<PsiMethod> targets = ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken));

        assertSize(1, targets);
        assertNotNull(targets.getFirst().getContainingClass());
        assertEquals("com.example.BaseMapper",
                targets.getFirst().getContainingClass().getQualifiedName());
    }

    public void testNonIdAttributeAndNestedStatementHaveNoTarget() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object named();
                    Object nested();
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select name="named">select 1</select>
                    <resultMap id="wrapper" type="java.lang.Object">
                        <select id="nested">select 1</select>
                    </resultMap>
                </mapper>
                """);
        XmlToken namedToken = findValueToken(xmlFile, "named");
        XmlToken nestedToken = findValueToken(xmlFile, "nested");

        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(namedToken)));
        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(nestedToken)));
        myFixture.doHighlighting();
        assertEmpty(mapperGutters());
    }

    public void testPrefixedIdAttributeDoesNotReceiveDuplicateGutter() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById();
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper" xmlns:custom="urn:test">
                    <select id="findById" custom:id="ignored">select 1</select>
                </mapper>
                """);
        XmlToken regularId = findValueToken(xmlFile, "findById");
        XmlToken prefixedId = findValueToken(xmlFile, "ignored");

        assertSize(1, ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(regularId)));
        assertEmpty(ReadAction.compute(
                () -> MyBatisXmlLineMarkerProvider.findTargets(prefixedId)));
        myFixture.doHighlighting();
        assertSize(1, mapperGutters());
    }

    public void testReturnsEmptyInDumbMode() throws Throwable {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertEmpty(
                ReadAction.compute(() -> MyBatisXmlLineMarkerProvider.findTargets(idToken))));
    }

    public void testLookupHonorsCancellation() {
        addMapperJava("""
                package com.example;
                public interface UserMapper {
                    Object findById(long id);
                }
                """);
        XmlFile xmlFile = configureMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        XmlToken idToken = findIdValueToken(xmlFile, "findById");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return ReadAction.compute(
                                () -> MyBatisXmlLineMarkerProvider.findTargets(idToken));
                    },
                    indicator);
            fail("取消后的查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，解析器不得吞掉。
        }
    }

    private PsiJavaFile addMapperJava(String source) {
        return (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/UserMapper.java",
                source);
    }

    private XmlFile configureMapperXml(String source) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", source);
    }

    private List<GutterMark> mapperGutters() {
        return myFixture.findAllGutters().stream()
                .filter(gutter -> MyBatisXmlLineMarkerProvider.TOOLTIP_TEXT.equals(
                        gutter.getTooltipText()))
                .toList();
    }

    private RelatedItemLineMarkerInfo<?> markerInfo(GutterMark gutter) {
        assertTrue(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?>);
        LineMarkerInfo<?> marker =
                ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) gutter).getLineMarkerInfo();
        assertTrue(marker instanceof RelatedItemLineMarkerInfo<?>);
        return (RelatedItemLineMarkerInfo<?>) marker;
    }

    private PsiMethod findMethod(PsiJavaFile javaFile, String methodName) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private XmlToken findIdValueToken(XmlFile xmlFile, String id) {
        return findValueToken(xmlFile, id);
    }

    private XmlToken findValueToken(XmlFile xmlFile, String value) {
        return PsiTreeUtil.findChildrenOfType(xmlFile, XmlToken.class).stream()
                .filter(token -> token.getTokenType() == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN)
                .filter(token -> value.equals(token.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends PsiElement> void navigate(LineMarkerInfo<T> markerInfo)
            throws Throwable {
        T source = markerInfo.getElement();
        assertNotNull(source);
        GutterIconNavigationHandler<T> navigationHandler = markerInfo.getNavigationHandler();
        assertNotNull(navigationHandler);
        EdtTestUtil.runInEdtAndWait(() -> navigationHandler.navigate(null, source));
    }
}
