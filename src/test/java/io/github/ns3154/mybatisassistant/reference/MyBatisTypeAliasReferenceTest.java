package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;

public final class MyBatisTypeAliasReferenceTest extends BasePlatformTestCase {
    private PsiClass user;
    private PsiClass account;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        user = addClass("com/example/User.java", """
                package com.example;
                public final class User {}
                """);
        account = addClass("com/example/Account.java", """
                package com.example;
                public final class Account {}
                """);
        myFixture.addFileToProject("src/main/resources/mybatis-config.xml", """
                <configuration>
                    <typeAliases>
                        <typeAlias alias="Person" type="com.example.User"/>
                        <typeAlias type="com.example.Account"/>
                    </typeAliases>
                </configuration>
                """);
    }

    public void testResultMapAndStatementTypeAttributesResolveAliasAndQualifiedClass() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Per<caret>son"/>
                    <select id="find" resultType="com.example.Account">select 1</select>
                </mapper>
                """);
        assertSame(user, reference().resolve());

        moveCaretTo("com.example.Account");
        assertSame(account, reference().resolve());
    }

    public void testNestedResultMappingTypeAttributesAreRecognized() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <association property="account" javaType="Acc<caret>ount"/>
                        <collection property="accounts" ofType="com.example.Account"/>
                    </resultMap>
                </mapper>
                """);
        assertSame(account, reference().resolve());

        moveCaretTo("com.example.Account");
        assertSame(account, reference().resolve());
    }

    public void testConflictingAliasesRemainMultipleTargets() {
        myFixture.addFileToProject("src/main/resources/another-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Shared" type="com.example.User"/>
                    <typeAlias alias="Shared" type="com.example.Account"/>
                </typeAliases></configuration>
                """);
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="sharedMap" type="Sha<caret>red"/>
                </mapper>
                """);

        ResolveResult[] results = multiResolve(reference());

        assertEquals(2, results.length);
        assertTrue(Arrays.stream(results).anyMatch(result -> user.equals(result.getElement())));
        assertTrue(Arrays.stream(results).anyMatch(result -> account.equals(result.getElement())));
    }

    public void testBuiltInPrimitiveArrayIsKnownAndOnlyMissingQualifiedTypeIsProvable() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="known" resultType="_int<caret>[]">select 1</select>
                </mapper>
                """);
        MyBatisTypeReference builtIn = (MyBatisTypeReference) reference();
        assertNull(builtIn.resolve());
        assertFalse(builtIn.isDefinitelyMissing());

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="missing" resultType="NoSuch<caret>Alias">select 1</select>
                </mapper>
                """);
        MyBatisTypeReference missing = (MyBatisTypeReference) reference();
        assertNull(missing.resolve());
        assertFalse(missing.isDefinitelyMissing());

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="missing" resultType="com.missing.NoSuch<caret>Type">select 1</select>
                </mapper>
                """);
        MyBatisTypeReference missingQualified = (MyBatisTypeReference) reference();
        assertNull(missingQualified.resolve());
        assertTrue(missingQualified.isDefinitelyMissing());
    }

    public void testDynamicTypeHasNoReferenceAndAliasVariantsAreAvailable() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="dynamic" type="${type<caret>.alias}"/>
                </mapper>
                """);
        assertNull(myFixture.getReferenceAtCaretPosition());

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Per<caret>son"/>
                </mapper>
                """);
        Object[] variants = reference().getVariants();
        assertTrue(Arrays.stream(variants).anyMatch(variant -> variant.toString().contains("Person")));
        assertTrue(Arrays.stream(variants).anyMatch(variant -> variant.toString().contains("string")));
    }

    public void testDumbModeIsUnresolvedAndCancellationPropagates() throws Throwable {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Per<caret>son"/>
                </mapper>
                """);
        PsiReference reference = reference();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertNull(reference.resolve()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return multiResolve(reference);
                    },
                    indicator);
            fail("取消后的 TypeAlias 引用解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能伪装成未解析别名。
        }
    }

    private PsiClass addClass(String path, String source) {
        PsiClass psiClass = PsiTreeUtil.findChildOfType(
                myFixture.addFileToProject("src/main/java/" + path, source),
                PsiClass.class);
        assertNotNull(psiClass);
        return psiClass;
    }

    private void configure(String xml) {
        myFixture.configureByText("UserMapper.xml", xml);
    }

    private PsiReference reference() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        assertNotNull(reference);
        assertTrue(reference instanceof MyBatisTypeReference);
        return reference;
    }

    private ResolveResult[] multiResolve(PsiReference reference) {
        assertTrue(reference instanceof PsiPolyVariantReference);
        return ((PsiPolyVariantReference) reference).multiResolve(false);
    }

    private void moveCaretTo(String needle) {
        String text = myFixture.getFile().getText();
        int offset = text.lastIndexOf(needle);
        assertTrue("测试文本中必须存在定位内容：" + needle, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset + Math.max(1, needle.length() / 2));
    }
}
