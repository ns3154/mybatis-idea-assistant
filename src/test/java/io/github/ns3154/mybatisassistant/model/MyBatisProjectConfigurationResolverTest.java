package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisProjectConfigurationResolverTest extends BasePlatformTestCase {
    public void testMergesNativeAndBootConfigurationDeterministically() {
        PsiFile context = myFixture.addFileToProject("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        myFixture.addFileToProject("src/main/resources/mybatis-config.xml", """
                <configuration>
                    <typeAliases><package name="com.example.domain"/></typeAliases>
                    <mappers>
                        <mapper resource="mapper/UserMapper.xml"/>
                        <mapper url="file:///opt/mapper/External.xml"/>
                        <mapper class="com.example.UserMapper"/>
                        <package name="com.example.mapper"/>
                    </mappers>
                </configuration>
                """);
        myFixture.addFileToProject("src/main/resources/application.properties", """
                mybatis.config-location=classpath:mybatis-config.xml
                mybatis.mapper-locations=classpath*:mapper/**/*.xml
                mybatis.type-aliases-package=com.example.domain,com.example.shared
                mybatis.type-handlers-package=com.example.handler
                """);

        MyBatisProjectConfigurationModel model = found(context);

        assertEquals(List.of("classpath:mybatis-config.xml"), model.configLocations());
        assertEquals(List.of("classpath*:mapper/**/*.xml"), model.mapperLocations());
        assertEquals(List.of("mapper/UserMapper.xml"), model.mapperResources());
        assertEquals(List.of("file:///opt/mapper/External.xml"), model.mapperUrls());
        assertEquals(List.of("com.example.UserMapper"), model.mapperClasses());
        assertEquals(List.of("com.example.mapper"), model.mapperPackages());
        assertEquals(
                List.of("com.example.domain", "com.example.shared"),
                model.typeAliasPackages());
        assertEquals(List.of("com.example.handler"), model.typeHandlerPackages());
    }

    public void testEmptyProjectReturnsEmptyModel() {
        PsiFile context = myFixture.addFileToProject("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);

        MyBatisProjectConfigurationModel model = found(context);

        assertEmpty(model.configLocations());
        assertEmpty(model.mapperLocations());
        assertEmpty(model.typeAliasPackages());
    }

    public void testUnsavedBootConfigurationChangeIsVisible() {
        PsiFile context = myFixture.addFileToProject("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        PsiFile configuration = myFixture.addFileToProject(
                "src/main/resources/application.properties",
                "mybatis.type-aliases-package=com.example.before\n");
        assertEquals(List.of("com.example.before"), found(context).typeAliasPackages());
        Document document = FileDocumentManager.getInstance().getDocument(configuration.getVirtualFile());
        assertNotNull(document);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText("mybatis.type-aliases-package=com.example.after\n");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertEquals(List.of("com.example.after"), found(context).typeAliasPackages());
    }

    public void testDumbModeAndDeletedContextReturnTypedStates() {
        PsiFile context = myFixture.addFileToProject("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                resolve(context),
                MyBatisProjectConfigurationResolution.IndexNotReady.class));

        WriteCommandAction.runWriteCommandAction(getProject(), context::delete);
        assertInstanceOf(resolve(context), MyBatisProjectConfigurationResolution.SourceInvalid.class);
    }

    private MyBatisProjectConfigurationResolution resolve(PsiFile context) {
        return ReadAction.compute(() -> MyBatisProjectConfigurationResolver.resolve(context));
    }

    private MyBatisProjectConfigurationModel found(PsiFile context) {
        MyBatisProjectConfigurationResolution resolution = resolve(context);
        assertInstanceOf(resolution, MyBatisProjectConfigurationResolution.Found.class);
        return ((MyBatisProjectConfigurationResolution.Found) resolution).model();
    }
}
