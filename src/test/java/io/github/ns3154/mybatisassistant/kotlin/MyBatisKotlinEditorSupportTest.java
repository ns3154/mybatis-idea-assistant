package io.github.ns3154.mybatisassistant.kotlin;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.inspection.MyBatisInvalidKotlinAnnotationParameterInspection;
import io.github.ns3154.mybatisassistant.inspection.MyBatisMissingKotlinStatementInspection;
import io.github.ns3154.mybatisassistant.navigation.MyBatisKotlinMapperLineMarkerProvider;
import io.github.ns3154.mybatisassistant.reference.MyBatisAnnotationParameterReference;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MyBatisKotlinEditorSupportTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationStubs();
    }

    public void testRegisteredKotlinGutterNavigatesToMapperXml() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);
        PsiFile kotlinFile = configureKotlin("""
                package com.example

                interface UserMapper {
                    fun findById(id: Long): User?
                }

                data class User(val id: Long)
                """);

        myFixture.doHighlighting();

        assertNotNull(onlyKotlinMarker("跳转到 MyBatis XML statement"));
        KtNamedFunction function = onlyFunction(kotlinFile, "findById");
        List<XmlTag> targets = ReadAction.compute(() ->
                MyBatisKotlinMapperLineMarkerProvider.findTargets(function)
                        .stream()
                        .filter(XmlTag.class::isInstance)
                        .map(XmlTag.class::cast)
                        .toList());
        assertSize(1, targets);
        assertEquals("findById", targets.getFirst().getAttributeValue("id"));
    }

    public void testXmlReverseNavigationTargetsKotlinFunction() {
        PsiFile kotlinFile = myFixture.addFileToProject(
                "src/main/kotlin/com/example/UserMapper.kt",
                """
                        package com.example
                        interface UserMapper {
                            fun findById(id: Long): Any?
                        }
                        """);
        XmlFile xmlFile = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select 1</select>
                </mapper>
                """);

        myFixture.doHighlighting();

        RelatedItemLineMarkerInfo<?> marker = relatedMarker(
                onlyMarker("跳转到 Java Mapper 方法"));
        List<PsiMethod> methods = relatedElements(marker, PsiMethod.class);
        assertSize(1, methods);
        assertTrue(PsiTreeUtil.isAncestor(
                kotlinFile,
                methods.getFirst().getNavigationElement(),
                false));
        assertEquals("findById", methods.getFirst().getName());
        assertNotNull(xmlFile.getRootTag());
    }

    public void testKotlinGutterPreservesMultipleMapperXmlTargets() {
        myFixture.addFileToProject("src/main/resources/first/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """);
        myFixture.addFileToProject("src/main/resources/second/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 2</select>
                </mapper>
                """);
        PsiFile kotlinFile = configureKotlin("""
                package com.example
                interface UserMapper {
                    fun find(id: Long): Any?
                }
                """);
        KtNamedFunction function = onlyFunction(kotlinFile, "find");

        List<PsiElement> targets = ReadAction.compute(() ->
                MyBatisKotlinMapperLineMarkerProvider.findTargets(function));

        assertSize(2, targets);
        assertTrue(targets.stream().allMatch(XmlTag.class::isInstance));
        assertNotNull(markers(function).getFirst().getNavigationHandler());
    }

    public void testKotlinAnnotationParameterReferencesDefaultAndDataClassProperties() {
        PsiFile kotlinFile = configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select * from users where name = #{query.name} limit #{limit}")
                    fun find(query: UserQuery?, limit: Int = 10): User?
                }

                data class UserQuery(val name: String)
                data class User(val id: Long)
                """);
        KtStringTemplateExpression literal = onlyString(kotlinFile);

        List<MyBatisAnnotationParameterReference> references = parameterReferences(literal);

        assertSize(3, references);
        assertEquals(List.of("query", "query.name", "limit"), references.stream()
                .map(MyBatisAnnotationParameterReference::parameterPath)
                .toList());
        assertTrue(references.stream().allMatch(reference -> reference.resolve() != null));
        assertTrue(references.stream()
                .map(PsiReference::resolve)
                .map(PsiElement::getNavigationElement)
                .anyMatch(KtParameter.class::isInstance));
    }

    public void testEscapedDollarPlaceholderResolvesInKotlinString() {
        PsiFile kotlinFile = configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select * from users where tenant = \\${tenant}")
                    fun find(tenant: String, active: Boolean): Any?
                }
                """);
        KtStringTemplateExpression literal = onlyString(kotlinFile);

        List<MyBatisAnnotationParameterReference> references = parameterReferences(literal);

        assertSize(1, references);
        assertEquals("tenant", references.getFirst().parameterPath());
        assertNotNull(references.getFirst().resolve());
        assertEquals("tenant", references.getFirst().getCanonicalText());
    }

    public void testKotlinAnnotationGutterTargetsSourceAnnotation() {
        PsiFile kotlinFile = configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select 1")
                    fun count(): Int
                }
                """);

        myFixture.doHighlighting();

        assertNotNull(onlyKotlinMarker("跳转到 MyBatis 注解 SQL"));
        KtNamedFunction function = onlyFunction(kotlinFile, "count");
        List<KtAnnotationEntry> targets = ReadAction.compute(() ->
                MyBatisKotlinMapperLineMarkerProvider.findTargets(function)
                        .stream()
                        .filter(KtAnnotationEntry.class::isInstance)
                        .map(KtAnnotationEntry.class::cast)
                        .toList());
        assertSize(1, targets);
        assertTrue(PsiTreeUtil.isAncestor(kotlinFile, targets.getFirst(), false));
    }

    public void testKotlinAnnotationReferenceProvidesNestedPropertyCompletion() {
        configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select * from users where name = #{query.na<caret>}")
                    fun find(query: UserQuery): Any?
                }

                data class UserQuery(val name: String, val nation: String)
                """);

        myFixture.completeBasic();

        assertContainsElements(myFixture.getLookupElementStrings(), "name", "nation");
    }

    public void testRegisteredKotlinInspectionsReportExactProblems() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper"></mapper>
                """);
        configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    fun missing(id: Long): Any?

                    @Select("select * from users where id = #{absent}")
                    fun annotated(id: Long): Any?
                }
                """);
        enableRegisteredInspection("MyBatisMissingKotlinStatement");
        enableRegisteredInspection("MyBatisInvalidKotlinAnnotationParameter");

        List<HighlightInfo> problems = myFixture.doHighlighting().stream()
                .filter(info -> info.getInspectionToolId() != null)
                .filter(info -> info.getInspectionToolId().startsWith("MyBatis"))
                .toList();

        assertSize(2, problems);
        assertEquals(
                List.of(
                        "未找到 MyBatis XML statement：com.example.UserMapper.missing",
                        "未找到 MyBatis 注解 SQL 参数路径：absent"),
                problems.stream().map(HighlightInfo::getDescription).sorted().toList());
        String fileText = myFixture.getFile().getText();
        assertEquals(
                List.of("absent", "missing"),
                problems.stream()
                        .map(problem -> fileText.substring(
                                problem.getStartOffset(),
                                problem.getEndOffset()))
                        .sorted()
                        .toList());
    }

    public void testMissingStatementInspectionIgnoresBodyOverloadAndAnnotationSql() {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper"></mapper>
                """);
        configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    fun withBody(): Int = 1
                    fun overloaded(id: Long): Any?
                    fun overloaded(id: String): Any?

                    @Select("select 1")
                    fun annotated(): Int
                }
                """);
        myFixture.enableInspections(new MyBatisMissingKotlinStatementInspection());

        assertEmpty(myFixture.doHighlighting().stream()
                .filter(info -> "MyBatisMissingKotlinStatement".equals(info.getInspectionToolId()))
                .toList());
    }

    public void testKotlinAnnotationInspectionIgnoresDynamicMapKey() {
        configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select * from users where value = #{runtimeKey}")
                    fun find(values: MutableMap<String, Any?>): Any?
                }
                """);
        myFixture.enableInspections(new MyBatisInvalidKotlinAnnotationParameterInspection());

        assertEmpty(myFixture.doHighlighting().stream()
                .filter(info -> "MyBatisInvalidKotlinAnnotationParameter".equals(
                        info.getInspectionToolId()))
                .toList());
    }

    public void testKotlinExtensionsAreSilentInDumbMode() throws Throwable {
        addMapperXml("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """);
        PsiFile kotlinFile = configureKotlin("""
                package com.example

                import org.apache.ibatis.annotations.Select

                interface UserMapper {
                    @Select("select * from users where id = #{id}")
                    fun find(id: Long): Any?
                }
                """);
        KtNamedFunction function = onlyFunction(kotlinFile, "find");
        KtStringTemplateExpression literal = onlyString(kotlinFile);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertEmpty(markers(function));
            assertEmpty(Arrays.stream(ReadAction.compute(literal::getReferences))
                    .filter(MyBatisAnnotationParameterReference.class::isInstance)
                    .toList());
        });
    }

    public void testKotlinGutterCancellationPropagates() {
        PsiFile kotlinFile = configureKotlin("""
                package com.example
                interface UserMapper {
                    fun find(id: Long): Any?
                }
                """);
        KtNamedFunction function = onlyFunction(kotlinFile, "find");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return markers(function);
                    },
                    indicator);
            fail("取消后的 Kotlin Mapper 导航必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，生产代码不得吞掉。
        }
    }

    private LineMarkerInfo<?> onlyMarker(String tooltip) {
        List<GutterMark> gutters = myFixture.findAllGutters().stream()
                .filter(gutter -> tooltip.equals(gutter.getTooltipText()))
                .toList();
        assertSize(1, gutters);
        GutterMark gutter = gutters.getFirst();
        assertTrue(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?>);
        LineMarkerInfo<?> marker =
                ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) gutter).getLineMarkerInfo();
        return marker;
    }

    private LineMarkerInfo<?> onlyKotlinMarker(String tooltip) {
        List<LineMarkerInfo<?>> markers = new ArrayList<>();
        for (GutterMark gutter : myFixture.findAllGutters()) {
            if (!(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?> renderer)) {
                continue;
            }
            LineMarkerInfo<?> marker = renderer.getLineMarkerInfo();
            if (marker.getClass() == LineMarkerInfo.class
                    && tooltip.equals(marker.getLineMarkerTooltip())) {
                markers.add(marker);
            }
        }
        assertSize(1, markers);
        return markers.getFirst();
    }

    private List<LineMarkerInfo<?>> markers(KtNamedFunction function) {
        LineMarkerInfo<?> marker = ReadAction.compute(() ->
                new MyBatisKotlinMapperLineMarkerProvider()
                        .getLineMarkerInfo(function.getNameIdentifier()));
        return marker == null ? List.of() : List.of(marker);
    }

    private List<MyBatisAnnotationParameterReference> parameterReferences(
            KtStringTemplateExpression literal) {
        return Arrays.stream(ReadAction.compute(literal::getReferences))
                .filter(MyBatisAnnotationParameterReference.class::isInstance)
                .map(MyBatisAnnotationParameterReference.class::cast)
                .toList();
    }

    private <T extends PsiElement> List<T> relatedElements(
            RelatedItemLineMarkerInfo<?> marker,
            Class<T> type) {
        return marker.createGotoRelatedItems().stream()
                .map(item -> item.getElement())
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private RelatedItemLineMarkerInfo<?> relatedMarker(LineMarkerInfo<?> marker) {
        assertTrue(marker instanceof RelatedItemLineMarkerInfo<?>);
        return (RelatedItemLineMarkerInfo<?>) marker;
    }

    private KtNamedFunction onlyFunction(PsiFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(file, KtNamedFunction.class).stream()
                .filter(function -> name.equals(function.getName()))
                .findFirst()
                .orElseThrow();
    }

    private KtStringTemplateExpression onlyString(PsiFile file) {
        List<KtStringTemplateExpression> literals = new ArrayList<>(
                PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression.class));
        assertSize(1, literals);
        return literals.getFirst();
    }

    private PsiFile configureKotlin(String source) {
        return myFixture.configureByText("UserMapper.kt", source);
    }

    private void addMapperXml(String xml) {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", xml);
    }

    private void enableRegisteredInspection(String shortName) {
        List<LocalInspectionEP> extensions = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList()
                .stream()
                .filter(extension -> shortName.equals(extension.getShortName()))
                .toList();
        assertSize(1, extensions);
        LocalInspectionTool tool = (LocalInspectionTool) extensions.getFirst().instantiateTool();
        if (shortName.equals("MyBatisMissingKotlinStatement")) {
            assertInstanceOf(tool, MyBatisMissingKotlinStatementInspection.class);
        } else {
            assertInstanceOf(tool, MyBatisInvalidKotlinAnnotationParameterInspection.class);
        }
        myFixture.enableInspections(tool);
    }

    private void addAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/kotlin/org/apache/ibatis/annotations/Select.kt",
                """
                        package org.apache.ibatis.annotations
                        annotation class Select(vararg val value: String)
                        """);
    }
}
