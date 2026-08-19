package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisTypeAliasResolverTest extends BasePlatformTestCase {
    public void testResolvesBuiltInAliasCaseInsensitively() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);

        MyBatisTypeAliasResolution resolution = resolve(context, " STRING ");

        assertInstanceOf(resolution, MyBatisTypeAliasResolution.Unique.class);
        assertEquals(
                "java.lang.String",
                ((MyBatisTypeAliasResolution.Unique) resolution).canonicalType());
        assertUnique("int[]", resolve(context, "_INTEGER[]"));
        assertUnique("java.sql.ResultSet", resolve(context, "ResultSet"));
    }

    public void testResolvesExplicitAndDefaultTypeAliases() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addClass("src/main/java/com/example/domain/User.java", """
                package com.example.domain;
                public final class User {}
                """);
        addClass("src/main/java/com/example/domain/Order.java", """
                package com.example.domain;
                public final class Order {}
                """);
        addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Person" type="com.example.domain.User"/>
                    <typeAlias type="com.example.domain.Order"/>
                </typeAliases></configuration>
                """);

        assertUnique("com.example.domain.User", resolve(context, "PERSON"));
        assertUnique("com.example.domain.Order", resolve(context, "order"));
    }

    public void testResolvesPackageAliasAndAliasAnnotation() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/type/Alias.java", """
                package org.apache.ibatis.type;
                public @interface Alias { String value(); }
                """);
        addClass("src/main/java/com/example/domain/User.java", """
                package com.example.domain;
                public final class User {}
                """);
        addClass("src/main/java/com/example/domain/Account.java", """
                package com.example.domain;
                import org.apache.ibatis.type.Alias;
                @Alias("customer")
                public final class Account {}
                """);
        addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <package name="com.example.domain"/>
                </typeAliases></configuration>
                """);

        assertUnique("com.example.domain.User", resolve(context, "user"));
        assertUnique("com.example.domain.Account", resolve(context, "CUSTOMER"));
        assertInstanceOf(resolve(context, "account"), MyBatisTypeAliasResolution.Unresolved.class);
    }

    public void testResolvesAcronymClassNameCaseInsensitivelyFromCachedNameDirectory() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addClass("src/main/java/com/example/domain/URLValue.java", """
                package com.example.domain;
                public final class URLValue {}
                """);
        addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <package name="com.example.domain"/>
                </typeAliases></configuration>
                """);

        MyBatisTypeAliasResolution first = resolve(context, "urlvalue");
        MyBatisTypeAliasResolution second = resolve(context, "URLVALUE");

        assertUnique("com.example.domain.URLValue", first);
        assertUnique("com.example.domain.URLValue", second);
    }

    public void testVariantsIncludeBuiltInExplicitPackageAndAnnotatedAliases() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        myFixture.addFileToProject("src/main/java/org/apache/ibatis/type/Alias.java", """
                package org.apache.ibatis.type;
                public @interface Alias { String value(); }
                """);
        addClass("src/main/java/com/example/domain/User.java", """
                package com.example.domain;
                public final class User {}
                """);
        addClass("src/main/java/com/example/domain/Account.java", """
                package com.example.domain;
                import org.apache.ibatis.type.Alias;
                @Alias("customer")
                public final class Account {}
                """);
        addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Person" type="com.example.domain.User"/>
                    <package name="com.example.domain"/>
                </typeAliases></configuration>
                """);

        List<String> variants = ReadAction.compute(
                () -> MyBatisTypeAliasResolver.variants(context));

        assertTrue(variants.containsAll(List.of("string", "Person", "User", "customer")));
        assertFalse("带 @Alias 的类型不能同时暴露默认简单类名", variants.contains("Account"));
    }

    public void testResolvesBootConfiguredTypeAliasPackage() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addClass("src/main/java/com/example/boot/BootUser.java", """
                package com.example.boot;
                public final class BootUser {}
                """);
        myFixture.addFileToProject("src/main/resources/application.yml", """
                mybatis:
                  type-aliases-package: com.example.boot
                """);

        assertUnique("com.example.boot.BootUser", resolve(context, "bootuser"));
    }

    public void testPreservesConflictingAliasTargets() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addClass("src/main/java/com/example/One.java", """
                package com.example;
                public final class One {}
                """);
        addClass("src/main/java/com/example/Two.java", """
                package com.example;
                public final class Two {}
                """);
        addConfig("config/one.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Shared" type="com.example.One"/>
                </typeAliases></configuration>
                """);
        addConfig("config/two.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Shared" type="com.example.Two"/>
                </typeAliases></configuration>
                """);

        MyBatisTypeAliasResolution resolution = resolve(context, "shared");

        assertInstanceOf(resolution, MyBatisTypeAliasResolution.Multiple.class);
        assertEquals(
                List.of("com.example.One", "com.example.Two"),
                ((MyBatisTypeAliasResolution.Multiple) resolution).canonicalTypes());
    }

    public void testUnsavedAliasChangeInvalidatesResolution() {
        PsiClass context = addClass("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        addClass("src/main/java/com/example/User.java", """
                package com.example;
                public final class User {}
                """);
        PsiFile config = addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Before" type="com.example.User"/>
                </typeAliases></configuration>
                """);
        assertUnique("com.example.User", resolve(context, "before"));
        XmlTag section = ((XmlFile) config).getRootTag().findFirstSubTag("typeAliases");
        assertNotNull(section);
        XmlTag declaration = section.findFirstSubTag("typeAlias");
        assertNotNull(declaration);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            declaration.setAttribute("alias", "After");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertInstanceOf(resolve(context, "before"), MyBatisTypeAliasResolution.Unresolved.class);
        assertUnique("com.example.User", resolve(context, "after"));
    }

    public void testDumbModeAndDeletedSourceReturnTypedStates() {
        PsiFile file = myFixture.addFileToProject("src/main/java/com/example/Context.java", """
                package com.example;
                public final class Context {}
                """);
        PsiClass context = PsiTreeUtil.findChildOfType(file, PsiClass.class);
        assertNotNull(context);

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertInstanceOf(
                resolve(context, "string"),
                MyBatisTypeAliasResolution.IndexNotReady.class));

        WriteCommandAction.runWriteCommandAction(getProject(), file::delete);
        assertInstanceOf(resolve(context, "string"), MyBatisTypeAliasResolution.SourceInvalid.class);
    }

    private PsiClass addClass(String path, String source) {
        PsiClass psiClass = PsiTreeUtil.findChildOfType(myFixture.addFileToProject(path, source), PsiClass.class);
        assertNotNull(psiClass);
        return psiClass;
    }

    private PsiFile addConfig(String path, String xml) {
        return myFixture.addFileToProject("src/main/resources/" + path, xml);
    }

    private MyBatisTypeAliasResolution resolve(PsiClass context, String alias) {
        return ReadAction.compute(() -> MyBatisTypeAliasResolver.resolve(context, alias));
    }

    private void assertUnique(String expected, MyBatisTypeAliasResolution resolution) {
        assertInstanceOf(resolution, MyBatisTypeAliasResolution.Unique.class);
        assertEquals(expected, ((MyBatisTypeAliasResolution.Unique) resolution).canonicalType());
    }
}
