package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisOgnlUnresolvedSymbolInspectionTest
        extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisOgnlUnresolvedSymbol";

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
                    public java.util.List<java.lang.String> getTags() {
                        return java.util.List.of();
                    }
                }
                """);
        myFixture.addFileToProject("src/main/java/com/example/UserMapper.java", """
                package com.example;
                import org.apache.ibatis.annotations.Param;
                public interface UserMapper {
                    Object find(@Param("user") com.example.User user,
                                @Param("values") java.util.Map<String, Object> values);
                }
                """);
        myFixture.enableInspections(extension().instantiateTool());
    }

    public void testRegistrationDefaultsAndDescriptionAreLoadable() {
        LocalInspectionEP extension = extension();
        InspectionProfileEntry tool = extension.instantiateTool();

        assertEquals("MyBatisOGNL", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("OGNL 符号未解析", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisOgnlUnresolvedSymbolInspection.class, tool.getClass());
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());
        assertNotNull(profileTool);
        assertNotNull(profileTool.loadDescription());
        assertTrue(profileTool.loadDescription().contains("不会执行 OGNL"));
    }

    public void testReportsOnlyProvablyMissingNamesAtExactRanges() {
        configure("""
                <bind name="pattern" value="'%' + user.name + '%'"/>
                <if test="missing != null or user.missing != null">x</if>
                <foreach collection="user.tags" item="tag" index="position">
                    <if test="tag != null and position >= 0 and pattern != null">x</if>
                </foreach>
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(2, warnings);
        assertEquals(
                List.of(
                        "未找到 MyBatis OGNL 符号：missing",
                        "未找到 MyBatis OGNL 符号：missing"),
                warnings.stream().map(HighlightInfo::getDescription).sorted().toList());
        assertEquals(
                List.of("missing", "missing"),
                warnings.stream().map(warning -> warning.getText()).sorted().toList());
        assertTrue(warnings.stream().allMatch(
                warning -> HighlightSeverity.WARNING.equals(warning.getSeverity())));
    }

    public void testDynamicMapAndUnsupportedContextStaySilent() {
        configure("""
                <if test="values.anything.deep != null">x</if>
                <if test="user.name != null">x</if>
                <if test="#root.dynamicValue != null and #this != null">x</if>
                """);
        myFixture.configureByText("Unsupported.xml", """
                <root><if test="missing.deep != null">x</if></root>
                """);

        assertEmpty(warnings());
    }

    public void testXmlEntityBeforeMissingNameKeepsExactHighlightRange() {
        configure("""
                <if test="1 &lt; missing">x</if>
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(1, warnings);
        assertEquals("missing", warnings.getFirst().getText());
        assertEquals("未找到 MyBatis OGNL 符号：missing",
                warnings.getFirst().getDescription());
    }

    public void testIncompleteOrUnsupportedSyntaxStaysSilent() {
        configure("""
                <if test="missing.">x</if>
                """);

        assertEmpty(warnings());
    }

    private LocalInspectionEP extension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList().stream()
                .filter(extension -> SHORT_NAME.equals(extension.getShortName()))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }

    private void configure(String body) {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">%s</select>
                </mapper>
                """.formatted(body));
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting().stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }
}
