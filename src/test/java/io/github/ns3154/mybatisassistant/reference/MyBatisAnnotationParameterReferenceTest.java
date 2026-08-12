package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import io.github.ns3154.mybatisassistant.inspection.MyBatisInvalidAnnotationParameterInspection;

import java.util.List;

public final class MyBatisAnnotationParameterReferenceTest extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisInvalidAnnotationParameter";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationStubs();
        myFixture.enableInspections(registeredExtension().instantiateTool());
    }

    public void testInspectionRegistrationAndDescription() {
        LocalInspectionEP extension = registeredExtension();
        InspectionProfileEntry tool = extension.instantiateTool();

        assertEquals("JAVA", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("MyBatis 注解 SQL 参数路径无效", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisInvalidAnnotationParameterInspection.class.getName(),
                extension.implementationClass);
        assertEquals(MyBatisInvalidAnnotationParameterInspection.class, tool.getClass());
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());
        assertNotNull(profileTool);
        String description = profileTool.loadDescription();
        assertNotNull(description);
        assertTrue(description, description.contains("动态 Map"));
        assertTrue(description, description.contains("保持静默"));
    }

    public void testResolvesExplicitParameterAndNestedProperty() {
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where name = #{filter.na<caret>me}")
                    Object find(@Param("filter") UserFilter filter);
                }

                final class UserFilter {
                    private String name;
                    public String getName() { return name; }
                }
                """);

        PsiLiteralExpression literal = sqlLiteral();
        assertNotNull(MyBatisAnnotationSqlSupport.inspect(literal));
        assertTrue(literal.getReferences().length > 0);
        PsiReference mergedReference = mergedReferenceAtCaret();
        assertInstanceOf(mergedReference, PsiPolyVariantReference.class);
        assertTrue(java.util.Arrays.stream(
                        ((PsiPolyVariantReference) mergedReference).multiResolve(false))
                .anyMatch(result -> result.getElement() instanceof com.intellij.psi.PsiMethod
                        || result.getElement() instanceof com.intellij.psi.PsiField));
        PsiReference reference = annotationReferenceAtCaret();
        assertInstanceOf(reference, MyBatisAnnotationParameterReference.class);
        PsiElement target = reference.resolve();
        assertNotNull(target);
        assertEquals("getName", target instanceof com.intellij.psi.PsiMethod method
                ? method.getName()
                : ((com.intellij.psi.PsiField) target).getName());
    }

    public void testCompletesRootAndNestedPropertyNames() {
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where name = #{filter.na<caret>}")
                    Object find(@Param("filter") UserFilter filter, @Param("limit") int limit);
                }

                final class UserFilter {
                    public String getName() { return ""; }
                    public int getAge() { return 0; }
                }
                """);

        PsiReference reference = annotationReferenceAtCaret();
        assertInstanceOf(reference, MyBatisAnnotationParameterReference.class);
        assertContainsElements(
                java.util.Arrays.stream(reference.getVariants())
                        .map(item -> ((LookupElement) item).getLookupString())
                        .toList(),
                "name",
                "age");
    }

    public void testInjectedSqlCompletionOffersAnnotationParameterPaths() {
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where name = #{filter.<caret>}")
                    Object find(@Param("filter") UserFilter filter, @Param("limit") int limit);
                }

                final class UserFilter {
                    public String getName() { return ""; }
                    public int getAge() { return 0; }
                }
                """);

        PsiLiteralExpression literal = sqlLiteral();
        assertContainsElements(
                MyBatisAnnotationSqlSupport.completionVariants(
                        literal,
                        hostOffsetAtCaret() - literal.getTextOffset()),
                "name",
                "age");
        assertTrue(com.intellij.codeInsight.completion.CompletionContributor
                .forLanguage(myFixture.getFile().getLanguage())
                .stream()
                .anyMatch(io.github.ns3154.mybatisassistant.sql.intellij
                        .MyBatisSqlCompletionContributor.class::isInstance));
        LookupElement[] items = myFixture.completeBasic();
        assertNotNull(items);
        assertContainsElements(
                java.util.Arrays.stream(items)
                        .map(LookupElement::getLookupString)
                        .toList(),
                "name",
                "age");
    }

    public void testInspectionReportsOnlyDefinitelyMissingPathSegment() {
        myFixture.enableInspections(new MyBatisInvalidAnnotationParameterInspection());
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where id = #{id} and x = #{filter.missing}")
                    Object find(@Param("id") long id, @Param("filter") UserFilter filter);
                }

                final class UserFilter {
                    public String getName() { return ""; }
                }
                """);

        List<HighlightInfo> problems = inspectionProblems();
        assertSize(1, problems);
        assertEquals("未找到 MyBatis 注解 SQL 参数路径：filter.missing",
                problems.getFirst().getDescription());
        assertEquals("missing", problems.getFirst().getText());
    }

    public void testDynamicMapAndUnrelatedAnnotationRemainSilent() {
        myFixture.enableInspections(new MyBatisInvalidAnnotationParameterInspection());
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import java.util.Map;
                import org.apache.ibatis.annotations.Select;

                @interface Query { String value(); }

                interface UserMapper {
                    @Select("select * from users where x = #{runtime.path}")
                    Object dynamic(Map<String, Object> runtime);

                    @Query("select * from users where x = #{missing}")
                    Object unrelated(long id);
                }
                """);

        assertEmpty(inspectionProblems());
    }

    public void testSelectListArrayLiteralsResolveParameters() {
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;
                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select.List({
                        @Select("select * from users where id = #{id}"),
                        @Select("select * from archive where id = #{i<caret>d}")
                    })
                    Object find(@Param("id") long id);
                }
                """);

        PsiReference reference = annotationReferenceAtCaret();
        assertInstanceOf(reference, MyBatisAnnotationParameterReference.class);
        assertNotNull(reference.resolve());
    }

    public void testDumbModeReturnsNoTargetsAndNoProblems() {
        myFixture.enableInspections(new MyBatisInvalidAnnotationParameterInspection());
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where id = #{mis<caret>sing}")
                    Object find(long id);
                }
                """);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertNull(annotationReferenceAtCaret());
        });
    }

    public void testCancellationPropagates() {
        myFixture.configureByText("UserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                interface UserMapper {
                    @Select("select * from users where id = #{i<caret>d}")
                    Object find(long id);
                }
                """);

        PsiReference reference = annotationReferenceAtCaret();
        assertNotNull(reference);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return reference.resolve();
                }, indicator));
    }

    private void addAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Select.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Select {
                            String[] value();
                            @interface List { Select[] value(); }
                        }
                        """);
    }

    private PsiReference annotationReferenceAtCaret() {
        PsiLiteralExpression literal = sqlLiteral();
        int offsetInLiteral = hostOffsetAtCaret() - literal.getTextOffset();
        for (PsiReference reference : literal.getReferences()) {
            if (reference instanceof MyBatisAnnotationParameterReference
                    && reference.getRangeInElement().containsOffset(offsetInLiteral)) {
                return reference;
            }
        }
        return null;
    }

    private PsiReference mergedReferenceAtCaret() {
        PsiLiteralExpression literal = sqlLiteral();
        return literal.findReferenceAt(hostOffsetAtCaret() - literal.getTextOffset());
    }

    private int hostOffsetAtCaret() {
        return InjectedLanguageManager.getInstance(getProject()).injectedToHost(
                myFixture.getFile(),
                myFixture.getEditor().getCaretModel().getOffset());
    }

    private PsiLiteralExpression sqlLiteral() {
        PsiFile hostFile = InjectedLanguageManager.getInstance(getProject())
                .getTopLevelFile(myFixture.getFile());
        return PsiTreeUtil.findChildrenOfType(hostFile, PsiLiteralExpression.class)
                .stream()
                .filter(candidate -> candidate.getText().contains("#{"))
                .filter(candidate -> candidate.getTextRange().containsOffset(
                        hostOffsetAtCaret()))
                .findFirst()
                .orElseThrow();
    }

    private List<HighlightInfo> inspectionProblems() {
        return myFixture.doHighlighting().stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }

    private LocalInspectionEP registeredExtension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList()
                .stream()
                .filter(extension -> SHORT_NAME.equals(extension.shortName))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }
}
