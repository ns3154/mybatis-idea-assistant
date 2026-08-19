package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.testutil.MyBatisPerformanceProgressIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MyBatisOgnlCompletionContributorTest extends BasePlatformTestCase {
    private static final int COLD_SAMPLE_COUNT = 30;

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

    public void testColdCompletionP95StaysWithinBudgetAndRunsInBackground()
            throws Exception {
        List<XmlFile> coldFiles = new ArrayList<>();
        for (int sample = 0; sample < COLD_SAMPLE_COUNT; sample++) {
            XmlFile xml = (XmlFile) myFixture.addFileToProject(
                    "src/main/resources/mapper/ColdUserMapper" + sample + ".xml",
                    """
                    <mapper namespace="com.example.UserMapper">
                        <select id="find"><if test="user.">x</if></select>
                    </mapper>
                    """);
            coldFiles.add(xml);
        }

        Future<CompletionBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    long[] durations = new long[coldFiles.size()];
                    List<List<String>> allVariants = new ArrayList<>();
                    boolean dispatchThread = ApplicationManager.getApplication()
                            .isDispatchThread();
                    for (int index = 0; index < coldFiles.size(); index++) {
                        XmlFile xml = coldFiles.get(index);
                        long started = System.nanoTime();
                        List<String> variants = ReadAction.compute(() -> {
                            PsiFile file = injectedFile(xml);
                            return
                                MyBatisOgnlCompletionContributor.completionVariants(
                                        file,
                                        file.getTextLength());
                        });
                        durations[index] = System.nanoTime() - started;
                        allVariants.add(variants);
                    }
                    return new CompletionBenchmark(dispatchThread, durations, allVariants);
                });

        CompletionBenchmark benchmark = future.get(30, TimeUnit.SECONDS);
        assertFalse("冷缓存补全计算不得占用 EDT", benchmark.dispatchThread());
        for (List<String> variants : benchmark.variants()) {
            assertContainsElements(variants, "name", "age", "active");
        }
        assertP95Below("冷缓存补全", benchmark.durations(), 500.0);
    }

    public void testColdCompletionCancelsDuringLargePropertyTraversalByCheckBudget()
            throws Exception {
        StringBuilder largeUser = new StringBuilder(
                "package com.example; public class LargeUser {");
        for (int index = 0; index < 400; index++) {
            largeUser.append("public String getField")
                    .append(index)
                    .append("() { return \"\"; }");
        }
        largeUser.append('}');
        myFixture.addFileToProject(
                "src/main/java/com/example/LargeUser.java",
                largeUser.toString());
        myFixture.addFileToProject("src/main/java/com/example/LargeMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface LargeMapper {
                    Object find(@Param("user") LargeUser user);
                }
                """);
        XmlFile xml = (XmlFile) myFixture.addFileToProject(
                "src/main/resources/mapper/LargeMapper.xml",
                """
                <mapper namespace="com.example.LargeMapper">
                    <select id="find"><if test="user.">x</if></select>
                </mapper>
                """);
        PsiFile injected = injectedFile(xml);

        Future<CancellationBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    MyBatisPerformanceProgressIndicator indicator =
                            new MyBatisPerformanceProgressIndicator();
                    boolean dispatchThread = ApplicationManager.getApplication()
                            .isDispatchThread();
                    try {
                        ProgressManager.getInstance().runProcess(
                                () -> ReadAction.compute(() -> {
                                    indicator.armCancellationAfterChecks(64);
                                    return MyBatisOgnlCompletionContributor
                                            .completionVariants(
                                                    injected,
                                                    injected.getTextLength());
                                }),
                                indicator);
                        return new CancellationBenchmark(
                                dispatchThread,
                                false,
                                indicator.checkCount());
                    } catch (ProcessCanceledException expected) {
                        return new CancellationBenchmark(
                                dispatchThread,
                                true,
                                indicator.checkCount());
                    }
                });

        CancellationBenchmark benchmark = future.get(30, TimeUnit.SECONDS);
        assertFalse("取消夹具必须在后台执行", benchmark.dispatchThread());
        assertTrue("冷缓存补全必须在大型属性遍历期间传播取消", benchmark.canceled());
        assertTrue("取消后不得继续无界遍历，检查次数=" + benchmark.checkCount(),
                benchmark.checkCount() >= 64 && benchmark.checkCount() <= 68);
    }

    private void configure(String expression) {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><if test="%s">x</if></select>
                </mapper>
                """.formatted(expression));
    }

    private PsiFile injectedFile() {
        return injectedFile((XmlFile) myFixture.getFile());
    }

    private PsiFile injectedFile(XmlFile xml) {
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

    private void assertP95Below(String path, long[] durations, double thresholdMillis) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        int percentileIndex = (int) Math.ceil(sorted.length * 0.95) - 1;
        double p95Millis = sorted[percentileIndex] / 1_000_000.0;
        System.out.println(path + " P95=" + p95Millis + "ms，样本数=" + sorted.length);
        assertTrue(
                path + " P95 为 " + p95Millis + "ms，阈值为 " + thresholdMillis + "ms",
                p95Millis < thresholdMillis);
    }

    private record CompletionBenchmark(
            boolean dispatchThread,
            long[] durations,
            List<List<String>> variants) {
    }

    private record CancellationBenchmark(
            boolean dispatchThread,
            boolean canceled,
            int checkCount) {
    }
}
