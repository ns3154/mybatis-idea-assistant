package io.github.ns3154.mybatisassistant.spring;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class MyBatisSpringInjectionLineMarkerProviderTest
        extends BasePlatformTestCase {
    private static final String TOOLTIP = "跳转到注入 Mapper 的 XML";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addInjectionAnnotationStubs();
    }

    public void testRegisteredProviderNavigatesAutowiredFieldToMapperXml() {
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        configureService("""
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;

                interface UserMapper {}

                final class UserService {
                    @Autowired
                    private UserMapper userMapper;
                }
                """);

        myFixture.doHighlighting();

        RelatedItemLineMarkerInfo<?> marker = onlyRegisteredMarker();
        List<XmlTag> targets = relatedElements(marker, XmlTag.class);
        assertSize(1, targets);
        assertEquals("com.example.UserMapper", targets.getFirst().getAttributeValue("namespace"));
    }

    public void testAutowiredConstructorMarksMapperParameter() {
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        PsiJavaFile javaFile = configureService("""
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;

                interface UserMapper {}

                final class UserService {
                    @Autowired
                    UserService(UserMapper userMapper) {}
                }
                """);

        assertSize(1, markers(variable(javaFile, "userMapper")));
    }

    public void testSupportsResourceAndInjectParameters() {
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        PsiJavaFile javaFile = configureService("""
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.inject.Inject;

                interface UserMapper {}

                final class UserService {
                    @Resource
                    private UserMapper resourceMapper;

                    void configure(@Inject UserMapper injectedMapper) {}
                }
                """);

        assertSize(1, markers(variable(javaFile, "resourceMapper")));
        assertSize(1, markers(variable(javaFile, "injectedMapper")));
    }

    public void testPreservesMultipleMapperXmlTargets() {
        addMapperXml("mapper/first/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        addMapperXml("mapper/second/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        PsiJavaFile javaFile = configureService("""
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;

                interface UserMapper {}

                final class UserService {
                    @Autowired
                    private UserMapper userMapper;
                }
                """);

        RelatedItemLineMarkerInfo<?> marker = markers(variable(javaFile, "userMapper")).getFirst();
        List<XmlTag> targets = relatedElements(marker, XmlTag.class);
        assertSize(2, targets);
        assertEquals(
                Set.of("mapper/first/UserMapper.xml", "mapper/second/UserMapper.xml"),
                targets.stream()
                        .map(XmlTag::getContainingFile)
                        .map(file -> file.getVirtualFile().getPath())
                        .map(path -> path.substring(path.indexOf("mapper/")))
                        .collect(Collectors.toSet()));
    }

    public void testIgnoresUnannotatedQualifierAndNonMapperVariables() {
        PsiJavaFile javaFile = configureService("""
                package com.example;

                import org.springframework.beans.factory.annotation.Qualifier;

                interface UserMapper {}
                final class OtherService {}

                final class UserService {
                    private UserMapper plainMapper;

                    @Qualifier("primary")
                    private UserMapper qualifiedOnlyMapper;

                    @jakarta.annotation.Resource
                    private OtherService otherService;
                }
                """);

        assertEmpty(markers(variable(javaFile, "plainMapper")));
        assertEmpty(markers(variable(javaFile, "qualifiedOnlyMapper")));
        assertEmpty(markers(variable(javaFile, "otherService")));
    }

    public void testReturnsNoMarkerInDumbMode() throws Throwable {
        addMapperXml("mapper/UserMapper.xml", """
                <mapper namespace="com.example.UserMapper"/>
                """);
        PsiJavaFile javaFile = configureService("""
                package com.example;

                interface UserMapper {}

                final class UserService {
                    @jakarta.annotation.Resource
                    private UserMapper userMapper;
                }
                """);
        PsiVariable variable = variable(javaFile, "userMapper");

        DumbModeTestUtils.runInDumbModeSynchronously(
                getProject(),
                () -> assertEmpty(markers(variable)));
    }

    public void testCancellationPropagates() {
        PsiJavaFile javaFile = configureService("""
                package com.example;

                interface UserMapper {}

                final class UserService {
                    @jakarta.annotation.Resource
                    private UserMapper userMapper;
                }
                """);
        PsiVariable variable = variable(javaFile, "userMapper");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return markers(variable);
                    },
                    indicator);
            fail("取消后的 Spring 注入导航必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，生产代码不得吞掉。
        }
    }

    private RelatedItemLineMarkerInfo<?> onlyRegisteredMarker() {
        List<GutterMark> gutters = myFixture.findAllGutters().stream()
                .filter(gutter -> TOOLTIP.equals(gutter.getTooltipText()))
                .toList();
        assertSize(1, gutters);
        GutterMark gutter = gutters.getFirst();
        assertTrue(gutter instanceof LineMarkerInfo.LineMarkerGutterIconRenderer<?>);
        LineMarkerInfo<?> marker =
                ((LineMarkerInfo.LineMarkerGutterIconRenderer<?>) gutter).getLineMarkerInfo();
        assertTrue(marker instanceof RelatedItemLineMarkerInfo<?>);
        return (RelatedItemLineMarkerInfo<?>) marker;
    }

    private List<RelatedItemLineMarkerInfo<?>> markers(PsiVariable variable) {
        Collection<RelatedItemLineMarkerInfo<?>> result = new ArrayList<>();
        ReadAction.run(() -> new MyBatisSpringInjectionLineMarkerProvider()
                .collectNavigationMarkers(variable.getNameIdentifier(), result));
        return List.copyOf(result);
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

    private PsiJavaFile configureService(String source) {
        return (PsiJavaFile) myFixture.configureByText("UserService.java", source);
    }

    private PsiVariable variable(PsiJavaFile javaFile, String name) {
        return PsiTreeUtil.findChildrenOfType(javaFile, PsiVariable.class).stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
    }

    private void addMapperXml(String path, String xml) {
        myFixture.addFileToProject("src/main/resources/" + path, xml);
    }

    private void addInjectionAnnotationStubs() {
        myFixture.addFileToProject(
                "src/main/java/org/springframework/beans/factory/annotation/Autowired.java",
                """
                        package org.springframework.beans.factory.annotation;
                        public @interface Autowired {}
                        """);
        myFixture.addFileToProject(
                "src/main/java/org/springframework/beans/factory/annotation/Qualifier.java",
                """
                        package org.springframework.beans.factory.annotation;
                        public @interface Qualifier { String value() default ""; }
                        """);
        myFixture.addFileToProject(
                "src/main/java/jakarta/annotation/Resource.java",
                """
                        package jakarta.annotation;
                        public @interface Resource {}
                        """);
        myFixture.addFileToProject(
                "src/main/java/jakarta/inject/Inject.java",
                """
                        package jakarta.inject;
                        public @interface Inject {}
                        """);
    }
}
