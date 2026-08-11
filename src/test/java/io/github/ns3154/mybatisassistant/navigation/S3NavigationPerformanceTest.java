package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.List;

public final class S3NavigationPerformanceTest extends BasePlatformTestCase {
    private static final int SAMPLE_COUNT = 100;

    public void testJavaToXmlColdAndHotNavigationP95() {
        Fixture fixture = createFixture();
        ReadAction.run(() -> assertSize(
                1,
                MyBatisMapperLineMarkerProvider.findTargets(fixture.methods()[0])));

        long[] coldDurations = new long[SAMPLE_COUNT];
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            PsiMethod method = fixture.methods()[index];
            long start = System.nanoTime();
            List<XmlTag> targets = ReadAction.compute(
                    () -> MyBatisMapperLineMarkerProvider.findTargets(method));
            coldDurations[index] = System.nanoTime() - start;
            assertSize(1, targets);
        }

        PsiMethod hotMethod = fixture.methods()[SAMPLE_COUNT - 1];
        long[] hotDurations = new long[SAMPLE_COUNT];
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            long start = System.nanoTime();
            List<XmlTag> targets = ReadAction.compute(
                    () -> MyBatisMapperLineMarkerProvider.findTargets(hotMethod));
            hotDurations[index] = System.nanoTime() - start;
            assertSize(1, targets);
        }

        assertP95Below("Java → XML 跳转", coldDurations, 300.0);
        assertP95Below("Java → XML 热缓存跳转", hotDurations, 100.0);
    }

    public void testXmlToJavaNavigationP95() {
        Fixture fixture = createFixture();
        XmlToken[] idTokens = fixture.idTokens();
        ReadAction.run(() -> assertSize(
                1,
                MyBatisXmlLineMarkerProvider.findTargets(idTokens[0])));

        long[] durations = new long[SAMPLE_COUNT];
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            XmlToken token = idTokens[index];
            long start = System.nanoTime();
            List<PsiMethod> targets = ReadAction.compute(
                    () -> MyBatisXmlLineMarkerProvider.findTargets(token));
            durations[index] = System.nanoTime() - start;
            assertSize(1, targets);
        }

        assertP95Below("XML → Java 跳转", durations, 300.0);
    }

    private Fixture createFixture() {
        StringBuilder java = new StringBuilder(
                "package com.example; public interface UserMapper {");
        StringBuilder xml = new StringBuilder(
                "<mapper namespace=\"com.example.UserMapper\">");
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            java.append("Object find").append(index).append("();");
            xml.append("<select id=\"find").append(index).append("\">select ")
                    .append(index).append("</select>");
        }
        java.append('}');
        xml.append("</mapper>");

        PsiJavaFile javaFile = (PsiJavaFile) myFixture.addFileToProject(
                "src/main/java/com/example/UserMapper.java",
                java.toString());
        XmlFile xmlFile = (XmlFile) myFixture.addFileToProject(
                "src/main/resources/mapper/UserMapper.xml",
                xml.toString());
        XmlTag root = xmlFile.getRootTag();
        assertNotNull(root);
        XmlTag[] statements = root.getSubTags();
        assertEquals(SAMPLE_COUNT, statements.length);
        XmlToken[] tokens = new XmlToken[SAMPLE_COUNT];
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            XmlAttributeValue idValue = statements[index]
                    .getAttribute("id")
                    .getValueElement();
            assertNotNull(idValue);
            tokens[index] = PsiTreeUtil.findChildrenOfType(idValue, XmlToken.class).stream()
                    .filter(token -> token.getTokenType()
                            == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN)
                    .findFirst()
                    .orElseThrow();
        }
        return new Fixture(javaFile.getClasses()[0].getMethods(), tokens);
    }

    private void assertP95Below(String path, long[] durations, double thresholdMillis) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        int percentileIndex = (int) Math.ceil(sorted.length * 0.95) - 1;
        double p95Millis = sorted[percentileIndex] / 1_000_000.0;
        assertTrue(
                path + " P95 为 " + p95Millis + "ms，阈值为 " + thresholdMillis + "ms",
                p95Millis < thresholdMillis);
    }

    private record Fixture(PsiMethod[] methods, XmlToken[] idTokens) {
    }
}
