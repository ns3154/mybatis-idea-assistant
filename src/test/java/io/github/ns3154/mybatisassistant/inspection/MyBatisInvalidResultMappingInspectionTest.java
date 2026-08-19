package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisInvalidResultMappingInspectionTest extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisInvalidResultMapping";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("src/main/java/com/example/User.java", """
                package com.example;
                public final class User {
                    private String name;
                    public void setName(String name) { this.name = name; }
                }
                """);
        myFixture.addFileToProject("src/main/resources/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Person" type="com.example.User"/>
                    <typeAlias alias="Broken" type="com.missing.Broken"/>
                </typeAliases></configuration>
                """);
        myFixture.enableInspections(extension().instantiateTool());
    }

    public void testRegistrationDefaultsAndDescriptionAreLoadable() {
        LocalInspectionEP extension = extension();
        assertEquals("XML", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("ResultMap 属性或类型未解析", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisInvalidResultMappingInspection.class, extension.instantiateTool().getClass());
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());
        assertNotNull(profileTool);
        assertTrue(profileTool.loadDescription().contains("代码动态注册的短 TypeAlias"));
    }

    public void testReportsKnownMissingPropertyQualifiedTypeAndBrokenExplicitAlias() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <result property="name" column="name"/>
                        <result property="missing" column="missing"/>
                    </resultMap>
                    <resultMap id="brokenMap" type="Broken"/>
                    <select id="missingType" resultType="com.missing.NoSuchType">select 1</select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings();

        assertEquals(3, warnings.size());
        assertEquals(
                List.of(
                        "未找到 MyBatis Java 类型：Broken",
                        "未找到 MyBatis Java 类型：com.missing.NoSuchType",
                        "未找到 ResultMap 可写属性：missing"),
                warnings.stream().map(HighlightInfo::getDescription).sorted().toList());
        assertTrue(warnings.stream().allMatch(
                warning -> HighlightSeverity.WARNING.equals(warning.getSeverity())));
    }

    public void testDynamicShortAliasColumnPrefixAndUnknownRootStaySilent() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="dynamic" type="${runtime.type}">
                        <association property="anything" columnPrefix="dynamic_"/>
                    </resultMap>
                    <resultMap id="programmatic" type="RuntimeAlias"/>
                </mapper>
                """);

        assertEmpty(warnings());
    }

    public void testDumbModeStaysSilent() {
        XmlFile file = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <result property="missing" column="missing"/>
                    </resultMap>
                </mapper>
                """);
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        XmlTag result = root.findFirstSubTag("resultMap").findFirstSubTag("result");
        XmlAttributeValue value = result.getAttribute("property").getValueElement();
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisInvalidResultMappingInspection()
                .buildVisitor(holder, true);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            value.accept(visitor);
            assertEmpty(holder.getResults());
        });
    }

    private LocalInspectionEP extension() {
        List<LocalInspectionEP> matches = LocalInspectionEP.LOCAL_INSPECTION
                .getExtensionList().stream()
                .filter(extension -> SHORT_NAME.equals(extension.getShortName()))
                .toList();
        assertSize(1, matches);
        return matches.getFirst();
    }

    private void configure(String xml) {
        myFixture.configureByText("UserMapper.xml", xml);
    }

    private List<HighlightInfo> warnings() {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }
}
