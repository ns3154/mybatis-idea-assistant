package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MyBatisOgnlCompletionContributorTest extends BasePlatformTestCase {
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
                    public boolean isActive() { return true; }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("user") com.example.User user,
                                @Param("limit") int limit);
                }
                """);
    }

    public void testRootCompletionUsesParameterAndBuiltInScope() {
        configure("<caret>");

        myFixture.completeBasic();
        List<String> variants = myFixture.getLookupElementStrings();

        assertNotNull(variants);
        assertContainsElements(variants, "user", "limit", "_databaseId", "_parameter");
    }

    public void testPropertyCompletionUsesResolvedJavaBeanType() {
        configure("user.<caret>");

        myFixture.completeBasic();
        List<String> variants = myFixture.getLookupElementStrings();

        assertNotNull(variants);
        assertContainsElements(variants, "name", "age", "active");
    }

    public void testDynamicMapDoesNotInventPropertyVariants() {
        myFixture.addFileToProject("src/main/java/com/example/MapMapper.java", """
                package com.example;
                public interface MapMapper {
                    Object find(java.util.Map<java.lang.String, java.lang.Object> values);
                }
                """);
        myFixture.configureByText("MapMapper.xml", """
                <mapper namespace="com.example.MapMapper">
                    <select id="find"><if test="anything.<caret>">x</if></select>
                </mapper>
                """);

        myFixture.completeBasic();
        List<String> variants = myFixture.getLookupElementStrings();

        assertTrue(variants == null || variants.isEmpty());
    }

    public void testHotCompletionP95StaysWithinInteractiveBudget() {
        configure("user.");
        PsiFile injected = injectedFile();
        for (int warmup = 0; warmup < 5; warmup++) {
            MyBatisOgnlCompletionContributor.completionVariants(
                    injected,
                    injected.getTextLength());
        }
        List<Long> durations = new ArrayList<>();
        for (int sample = 0; sample < 100; sample++) {
            long started = System.nanoTime();
            List<String> variants = MyBatisOgnlCompletionContributor.completionVariants(
                    injected,
                    injected.getTextLength());
            durations.add(System.nanoTime() - started);
            assertContainsElements(variants, "name", "age", "active");
        }
        Collections.sort(durations);
        long p95Nanos = durations.get((int) Math.ceil(durations.size() * 0.95) - 1);
        long p95Millis = p95Nanos / 1_000_000;
        assertTrue("热缓存补全 P95 超过 150ms，实际=" + p95Millis + "ms，样本=" + durations,
                p95Nanos < 150_000_000L);
    }

    private void configure(String expression) {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="%s">x</if></select>
                </mapper>
                """.formatted(expression));
    }

    private PsiFile injectedFile() {
        XmlFile xml = (XmlFile) myFixture.getFile();
        XmlAttributeValue host = PsiTreeUtil.findChildrenOfType(
                xml,
                XmlAttribute.class).stream()
                .filter(attribute -> "test".equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        List<Pair<PsiElement, TextRange>> injected = InjectedLanguageManager
                .getInstance(getProject())
                .getInjectedPsiFiles(host);
        assertNotNull(injected);
        assertSize(1, injected);
        PsiElement element = injected.getFirst().getFirst();
        return element instanceof PsiFile file ? file : element.getContainingFile();
    }
}
