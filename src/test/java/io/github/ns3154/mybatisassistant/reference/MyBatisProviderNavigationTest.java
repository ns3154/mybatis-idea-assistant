package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.navigation.MyBatisMapperLineMarkerProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class MyBatisProviderNavigationTest extends BasePlatformTestCase {
    private static final String PROVIDER_TOOLTIP = "跳转到 MyBatis Provider 方法";
    private static final String ANNOTATION_TOOLTIP = "跳转到 MyBatis 注解 SQL";
    private static final String XML_TOOLTIP = "跳转到 MyBatis XML statement";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addMyBatisAnnotationStubs();
    }

    public void testProviderMethodLiteralResolvesOverloadsAndSupportsFindUsages() {
        PsiJavaFile javaFile = configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider(type = UserSqlProvider.class, method = "find<caret>Sql")
                    Object findById(long id);
                }

                final class UserSqlProvider {
                    public static String findSql(long id) { return "select 1"; }
                    public static CharSequence findSql(String id) { return "select 2"; }
                }
                """);

        PsiReference reference = providerReferenceAtCaret();
        ResolveResult[] results = multiResolve(reference);

        assertEquals("findSql", reference.getCanonicalText());
        assertEquals(2, results.length);
        assertTrue(Arrays.stream(results)
                .allMatch(result -> result.getElement() instanceof PsiMethod));
        for (ResolveResult result : results) {
            PsiMethod providerMethod = (PsiMethod) result.getElement();
            Collection<PsiReference> usages = ReadAction.compute(
                    () -> ReferencesSearch.search(providerMethod).findAll());
            assertTrue(usages.stream().anyMatch(candidate ->
                    candidate.getElement() == reference.getElement()));
        }
        assertEquals(javaFile, reference.getElement().getContainingFile());
    }

    public void testRegisteredGuttersExposeProviderAndInlineAnnotationTargets() {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="findById">select from xml</select>
                    <select id="countUsers">select from xml</select>
                </mapper>
                """);
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider(type = UserSqlProvider.class, method = "findSql")
                    Object findById(long id);

                    @Select("select count(*) from user")
                    int countUsers();
                }

                final class UserSqlProvider {
                    public static String findSql(long id) { return "select 1"; }
                    public static String findSql(String id) { return "select 2"; }
                }
                """);

        myFixture.doHighlighting();

        RelatedItemLineMarkerInfo<?> providerMarker = onlyMarker(PROVIDER_TOOLTIP);
        List<PsiMethod> providerMethods = relatedElements(providerMarker, PsiMethod.class);
        assertSize(2, providerMethods);
        assertTrue(providerMethods.get(0).getTextOffset() < providerMethods.get(1).getTextOffset());

        RelatedItemLineMarkerInfo<?> annotationMarker = onlyMarker(ANNOTATION_TOOLTIP);
        List<PsiAnnotation> annotations = relatedElements(annotationMarker, PsiAnnotation.class);
        assertSize(1, annotations);
        assertEquals("org.apache.ibatis.annotations.Select",
                annotations.getFirst().getQualifiedName());
        assertEmpty(gutters(XML_TOOLTIP));
    }

    public void testProviderListCollectsEveryExplicitTarget() {
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider.List({
                        @SelectProvider(type = FirstProvider.class, method = "firstSql"),
                        @SelectProvider(type = SecondProvider.class, method = "secondSql")
                    })
                    Object findById(long id);
                }

                final class FirstProvider {
                    public static String firstSql(long id) { return "select 1"; }
                }

                final class SecondProvider {
                    public static String secondSql(long id) { return "select 2"; }
                }
                """);

        myFixture.doHighlighting();

        RelatedItemLineMarkerInfo<?> marker = onlyMarker(PROVIDER_TOOLTIP);
        List<PsiMethod> methods = relatedElements(marker, PsiMethod.class);
        assertEquals(List.of("firstSql", "secondSql"),
                methods.stream().map(PsiMethod::getName).toList());

        List<PsiLiteralExpression> methodLiterals = PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(),
                        PsiLiteralExpression.class)
                .stream()
                .filter(literal -> literal.getParent().getText().startsWith("method"))
                .toList();
        assertSize(2, methodLiterals);
        for (PsiLiteralExpression literal : methodLiterals) {
            List<PsiReference> references = providerReferences(literal);
            assertSize(1, references);
            assertSize(1, multiResolve(references.getFirst()));
        }
    }

    public void testInvalidMissingAndUnrelatedProviderMetadataRemainConservative() {
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider(type = UserSqlProvider.class)
                    Object defaultMethodName();

                    @SelectProvider(type = UserSqlProvider.class, method = "mis<caret>sing")
                    Object missingMethod();

                    @OtherProvider(type = UserSqlProvider.class, method = "findSql")
                    Object unrelatedAnnotation();
                }

                @interface OtherProvider {
                    Class<?> type();
                    String method();
                }

                final class UserSqlProvider {
                    public static String findSql() { return "select 1"; }
                }
                """);

        PsiReference missingReference = providerReferenceAtCaret();
        assertEmpty(multiResolve(missingReference));
        myFixture.doHighlighting();
        List<GutterMark> providerGutters = gutters(PROVIDER_TOOLTIP);
        assertSize(2, providerGutters);
        for (GutterMark gutter : providerGutters) {
            RelatedItemLineMarkerInfo<?> marker = markerInfo(gutter);
            List<PsiClass> providerClasses = relatedElements(marker, PsiClass.class);
            assertSize(1, providerClasses);
            assertEquals("com.example.UserSqlProvider",
                    providerClasses.getFirst().getQualifiedName());
        }

        PsiLiteralExpression unrelatedLiteral = PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(),
                        PsiLiteralExpression.class)
                .stream()
                .filter(literal -> "findSql".equals(literal.getValue()))
                .findFirst()
                .orElseThrow();
        assertEmpty(providerReferences(unrelatedLiteral));
    }

    public void testDefaultFallbackAndDynamicProviderHaveTruthfulTargets() {
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;
                import org.apache.ibatis.builder.annotation.ProviderMethodResolver;

                public interface UserMapper {
                    @SelectProvider(type = FallbackProvider.class)
                    Object fallback(long id);

                    @SelectProvider(type = DynamicProvider.class)
                    Object dynamic(long id);
                }

                final class FallbackProvider {
                    public static String provideSql(long id) { return "select 1"; }
                }

                final class DynamicProvider implements ProviderMethodResolver {
                    public static String chooseAtRuntime(long id) { return "select 2"; }
                }
                """);

        myFixture.doHighlighting();

        RelatedItemLineMarkerInfo<?> fallbackMarker = providerMarkerFor("fallback");
        List<PsiMethod> fallbackTargets = relatedElements(fallbackMarker, PsiMethod.class);
        assertSize(1, fallbackTargets);
        assertEquals("provideSql", fallbackTargets.getFirst().getName());

        RelatedItemLineMarkerInfo<?> dynamicMarker = providerMarkerFor("dynamic");
        List<PsiClass> dynamicTargets = relatedElements(dynamicMarker, PsiClass.class);
        assertSize(1, dynamicTargets);
        assertEquals("com.example.DynamicProvider",
                dynamicTargets.getFirst().getQualifiedName());
        assertEmpty(relatedElements(dynamicMarker, PsiMethod.class));
    }

    public void testProviderAliasesAndInvalidRuntimeMethodStayNavigable() {
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider(
                        type = void.class,
                        value = AliasProvider.class,
                        method = "sql"
                    )
                    Object aliasProvider();

                    @SelectProvider(type = InvalidProvider.class, method = "sql")
                    Object invalidProvider();
                }

                final class AliasProvider {
                    public static String sql() { return "select 1"; }
                }

                final class InvalidProvider {
                    public static Object sql() { return new Object(); }
                }
                """);

        myFixture.doHighlighting();

        List<PsiMethod> aliasTargets = relatedElements(
                providerMarkerFor("aliasProvider"),
                PsiMethod.class);
        assertSize(1, aliasTargets);
        assertEquals("AliasProvider",
                aliasTargets.getFirst().getContainingClass().getName());

        RelatedItemLineMarkerInfo<?> invalidMarker = providerMarkerFor("invalidProvider");
        assertEmpty(relatedElements(invalidMarker, PsiMethod.class));
        List<PsiClass> invalidTargets = relatedElements(invalidMarker, PsiClass.class);
        assertSize(1, invalidTargets);
        assertEquals("com.example.InvalidProvider",
                invalidTargets.getFirst().getQualifiedName());
    }

    public void testAllSqlAnnotationFamiliesUseTheirOwnNavigationPath() {
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="flushStatements">select from xml</select>
                </mapper>
                """);
        configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.Delete;
                import org.apache.ibatis.annotations.DeleteProvider;
                import org.apache.ibatis.annotations.Flush;
                import org.apache.ibatis.annotations.Insert;
                import org.apache.ibatis.annotations.InsertProvider;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.SelectProvider;
                import org.apache.ibatis.annotations.Update;
                import org.apache.ibatis.annotations.UpdateProvider;

                public interface UserMapper {
                    @Select("select 1") Object selectInline();
                    @Insert("insert") int insertInline();
                    @Update("update") int updateInline();
                    @Delete("delete") int deleteInline();

                    @SelectProvider(type = SqlProvider.class, method = "selectSql")
                    Object selectProvider();
                    @InsertProvider(type = SqlProvider.class, method = "insertSql")
                    int insertProvider();
                    @UpdateProvider(type = SqlProvider.class, method = "updateSql")
                    int updateProvider();
                    @DeleteProvider(type = SqlProvider.class, method = "deleteSql")
                    int deleteProvider();

                    @Flush Object flushStatements();
                }

                final class SqlProvider {
                    public static String selectSql() { return "select 1"; }
                    public static String insertSql() { return "insert"; }
                    public static String updateSql() { return "update"; }
                    public static String deleteSql() { return "delete"; }
                }
                """);

        myFixture.doHighlighting();

        assertSize(4, gutters(ANNOTATION_TOOLTIP));
        assertSize(4, gutters(PROVIDER_TOOLTIP));
        assertEmpty(gutters(XML_TOOLTIP));
        long providerReferences = PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(),
                        PsiLiteralExpression.class)
                .stream()
                .mapToLong(literal -> providerReferences(literal).size())
                .sum();
        assertEquals(4L, providerReferences);
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Throwable {
        PsiJavaFile javaFile = configureJava("""
                package com.example;

                import org.apache.ibatis.annotations.SelectProvider;

                public interface UserMapper {
                    @SelectProvider(type = UserSqlProvider.class, method = "find<caret>Sql")
                    Object findById(long id);
                }

                final class UserSqlProvider {
                    public static String findSql(long id) { return "select 1"; }
                }
                """);
        PsiReference reference = providerReferenceAtCaret();
        PsiMethod mapperMethod = findMethod(javaFile, "findById");

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertEmpty(multiResolve(reference));
            List<RelatedItemLineMarkerInfo<?>> markers = new ArrayList<>();
            new MyBatisMapperLineMarkerProvider().collectNavigationMarkers(
                    mapperMethod.getNameIdentifier(),
                    markers);
            assertEmpty(markers);
            assertEmpty(providerReferences((PsiLiteralExpression) reference.getElement()));
        });

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return multiResolve(reference);
                    },
                    indicator);
            fail("取消后的 Provider 查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转换为空结果。
        }
    }

    private void addMyBatisAnnotationStubs() {
        for (String annotationName : List.of("Select", "Insert", "Update", "Delete")) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/"
                            + annotationName + ".java",
                    "package org.apache.ibatis.annotations;\n"
                            + "public @interface " + annotationName
                            + " { String[] value(); }\n");
        }
        for (String annotationName : List.of(
                "SelectProvider",
                "InsertProvider",
                "UpdateProvider",
                "DeleteProvider")) {
            myFixture.addFileToProject(
                    "src/main/java/org/apache/ibatis/annotations/"
                            + annotationName + ".java",
                    "package org.apache.ibatis.annotations;\n"
                            + "public @interface " + annotationName + " {\n"
                            + "  Class<?> value() default void.class;\n"
                            + "  Class<?> type() default void.class;\n"
                            + "  String method() default \"\";\n"
                            + "  @interface List { " + annotationName + "[] value(); }\n"
                            + "}\n");
        }
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Flush.java",
                """
                        package org.apache.ibatis.annotations;

                        public @interface Flush {
                        }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/builder/annotation/ProviderMethodResolver.java",
                """
                        package org.apache.ibatis.builder.annotation;

                        public interface ProviderMethodResolver {
                        }
                        """);
    }

    private PsiJavaFile configureJava(String source) {
        return (PsiJavaFile) myFixture.configureByText("UserMapper.java", source);
    }

    private PsiReference providerReferenceAtCaret() {
        PsiElement leaf = myFixture.getFile().findElementAt(
                myFixture.getEditor().getCaretModel().getOffset());
        assertNotNull(leaf);
        PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(
                leaf,
                PsiLiteralExpression.class,
                false);
        assertNotNull(literal);
        List<PsiReference> references = providerReferences(literal);
        assertSize(1, references);
        return references.getFirst();
    }

    private List<PsiReference> providerReferences(PsiLiteralExpression literal) {
        return Arrays.stream(ReadAction.compute(literal::getReferences))
                .filter(MyBatisProviderMethodReference.class::isInstance)
                .toList();
    }

    private ResolveResult[] multiResolve(PsiReference reference) {
        assertTrue(reference instanceof PsiPolyVariantReference);
        return ReadAction.compute(
                () -> ((PsiPolyVariantReference) reference).multiResolve(false));
    }

    private List<GutterMark> gutters(String tooltip) {
        return myFixture.findAllGutters().stream()
                .filter(gutter -> tooltip.equals(gutter.getTooltipText()))
                .toList();
    }

    private RelatedItemLineMarkerInfo<?> onlyMarker(String tooltip) {
        List<GutterMark> gutters = gutters(tooltip);
        assertSize(1, gutters);
        return markerInfo(gutters.getFirst());
    }

    private RelatedItemLineMarkerInfo<?> providerMarkerFor(String mapperMethodName) {
        return gutters(PROVIDER_TOOLTIP).stream()
                .map(this::markerInfo)
                .filter(marker -> marker.getElement().getParent() instanceof PsiMethod method
                        && mapperMethodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }

    private RelatedItemLineMarkerInfo<?> markerInfo(GutterMark gutter) {
        assertTrue(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?>);
        LineMarkerInfo<?> marker =
                ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) gutter).getLineMarkerInfo();
        assertTrue(marker instanceof RelatedItemLineMarkerInfo<?>);
        return (RelatedItemLineMarkerInfo<?>) marker;
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

    private PsiMethod findMethod(PsiJavaFile javaFile, String methodName) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiMethod.class).stream()
                .filter(method -> methodName.equals(method.getName()))
                .findFirst()
                .orElseThrow();
    }
}
