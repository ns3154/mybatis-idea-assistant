package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlToken;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisInvalidParameterPathInspectionTest extends BasePlatformTestCase {
    private static final String SHORT_NAME = "MyBatisInvalidParameterPath";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        myFixture.addFileToProject(
                "src/main/java/com/example/UserMapper.java",
                """
                        package com.example;
                        import org.apache.ibatis.annotations.Param;
                        public interface UserMapper {
                            Object find(@Param("user") User user,
                                        @Param("items") java.util.List<Item> items);
                            Object dynamic(java.util.Map<String, Object> values);
                        }
                        final class User { String name; }
                        final class Item { String code; }
                        """);
        myFixture.enableInspections(extension().instantiateTool());
    }

    public void testRegistrationDefaultsAndDescriptionAreLoadable() {
        LocalInspectionEP extension = extension();
        InspectionProfileEntry tool = extension.instantiateTool();

        assertEquals("XML", extension.language);
        assertTrue(extension.enabledByDefault);
        assertEquals(HighlightDisplayLevel.WARNING, extension.getDefaultLevel());
        assertEquals("Mapper XML 参数或属性未解析", extension.getDisplayName());
        assertEquals("MyBatis", extension.getGroupDisplayName());
        assertEquals(MyBatisInvalidParameterPathInspection.class, tool.getClass());
        InspectionToolWrapper<?, ?> profileTool = InspectionProfileManager
                .getInstance(getProject())
                .getCurrentProfile()
                .getInspectionTool(SHORT_NAME, getProject());
        assertNotNull(profileTool);
        assertNotNull(profileTool.loadDescription());
        assertTrue(profileTool.loadDescription().contains("Map 键"));
    }

    public void testReportsOnlyProvablyMissingRootAndNestedProperty() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users
                        where a = #{missing.name}
                          and b = #{user.missing}
                          and c = #{user.name}
                          and d = #{items[0].code}
                    </select>
                </mapper>
                """);

        List<HighlightInfo> warnings = warnings();

        assertSize(2, warnings);
        assertEquals(
                List.of(
                        "未找到 MyBatis 参数或属性：missing",
                        "未找到 MyBatis 参数或属性：user.missing"),
                warnings.stream().map(HighlightInfo::getDescription).sorted().toList());
        assertTrue(warnings.stream().allMatch(
                warning -> HighlightSeverity.WARNING.equals(warning.getSeverity())));
    }

    public void testMapDynamicIndexActualNameAndMissingMethodStaySilent() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="dynamic">select #{unknown.deep} from users</select>
                    <select id="find">
                        <bind name="pattern" value="'%' + user.name + '%'"/>
                        select #{items[key].missing}, #{user.name}, #{pattern} from users
                        <foreach collection="items" item="item" index="position">
                            #{item.code}, #{position}, #{unmodeledLocal.value}
                        </foreach>
                    </select>
                    <select id="notDeclared">select #{anything.missing} from users</select>
                </mapper>
                """);

        assertEmpty(warnings());
    }

    public void testDumbModeStaysSilent() {
        XmlFile file = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select #{user.missing} from users</select>
                </mapper>
                """);
        int offset = file.getText().indexOf("user.missing");
        PsiElement element = file.findElementAt(offset);
        assertTrue(element instanceof XmlToken);
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                file,
                true);
        PsiElementVisitor visitor = new MyBatisInvalidParameterPathInspection()
                .buildVisitor(holder, true);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            element.accept(visitor);
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
        return myFixture.doHighlighting().stream()
                .filter(info -> SHORT_NAME.equals(info.getInspectionToolId()))
                .toList();
    }
}
