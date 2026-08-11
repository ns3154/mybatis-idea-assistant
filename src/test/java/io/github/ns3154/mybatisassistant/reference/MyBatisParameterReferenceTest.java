package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;

public final class MyBatisParameterReferenceTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject(
                "src/main/java/org/apache/ibatis/annotations/Param.java",
                """
                        package org.apache.ibatis.annotations;
                        public @interface Param { String value(); }
                        """);
        addMapper();
    }

    public void testPlaceholderRootNestedPropertyAndIndexedElementResolvePrecisely() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where name = #{us<caret>er.name}</select>
                </mapper>
                """);
        assertTrue(reference().resolve() instanceof PsiParameter);

        moveCaretTo("name}");
        assertField(reference().resolve(), "name");

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where code = #{items[0].co<caret>de}</select>
                </mapper>
                """);
        moveCaretTo("items[0]");
        PsiElement indexedRoot = reference().resolve();
        assertTrue(indexedRoot instanceof PsiParameter);
        assertEquals("java.util.List<Item>",
                ((PsiParameter) indexedRoot).getType().getCanonicalText());
        moveCaretTo("code}");
        PsiReference indexedReference = reference();
        assertEquals("code", indexedReference.getCanonicalText());
        assertNotNull("索引属性解析状态："
                        + ((MyBatisParameterReference) indexedReference).resolutionStatus(),
                indexedReference.resolve());
        assertField(indexedReference.resolve(), "code");

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select ${user.na<caret>me} from users</select>
                </mapper>
                """);
        assertField(reference().resolve(), "name");
    }

    public void testPlaceholderOptionsDoNotBecomePartOfPropertyPath() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users where name =
                        #{user.na<caret>me,jdbcType=VARCHAR,typeHandler=com.example.Handler}
                    </select>
                </mapper>
                """);

        PsiReference reference = reference();

        assertEquals("name", reference.getCanonicalText());
        assertField(reference.resolve(), "name");
    }

    public void testIncompletePlaceholderAndArrayIndexRemainEditable() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select #{user.na<caret>me</select>
                </mapper>
                """);
        assertField(reference().resolve(), "name");

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="array">select #{array[0].co<caret>de} from items</select>
                </mapper>
                """);
        assertField(reference().resolve(), "code");
    }

    public void testForeachCollectionAndKeyPropertyAttributesUseStatementContext() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select * from users where id in
                        <foreach collection="it<caret>ems" item="item">#{item.code}</foreach>
                    </select>
                </mapper>
                """);
        assertTrue(reference().resolve() instanceof PsiParameter);

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <insert id="insert" keyProperty="us<caret>er.id">
                        insert into users(name) values(#{user.name})
                    </insert>
                </mapper>
                """);
        assertTrue(reference().resolve() instanceof PsiParameter);

        moveCaretTo("id\"");
        assertField(reference().resolve(), "id");
    }

    public void testSingleObjectAllowsDirectPropertyAndDynamicMapRemainsUnknown() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="direct">select * from users where name = #{na<caret>me}</select>
                </mapper>
                """);
        assertField(reference().resolve(), "name");

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="dynamic">select * from users where value = #{unk<caret>nownKey}</select>
                </mapper>
                """);
        PsiReference mapReference = reference();
        assertNull(mapReference.resolve());
        assertFalse(((MyBatisParameterReference) mapReference).isDefinitelyMissing());
    }

    public void testReadableBeanPropertyPrefersGetterOverBackingField() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="getter">select #{la<caret>bel} from users</select>
                </mapper>
                """);

        PsiElement target = reference().resolve();

        assertTrue(target instanceof PsiMethod);
        assertEquals("getLabel", ((PsiMethod) target).getName());
    }

    public void testProvablyMissingRootAndNestedPropertyAreDistinguishedFromUnknown() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where value = #{miss<caret>ing.name}</select>
                </mapper>
                """);
        MyBatisParameterReference missingRoot = (MyBatisParameterReference) reference();
        assertNull(missingRoot.resolve());
        assertTrue(missingRoot.isDefinitelyMissing());

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where value = #{user.miss<caret>ing}</select>
                </mapper>
                """);
        MyBatisParameterReference missingProperty = (MyBatisParameterReference) reference();
        assertNull(missingProperty.resolve());
        assertTrue(missingProperty.isDefinitelyMissing());
    }

    public void testPartialPropertyProvidesCompletionVariants() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where name = #{user.na<caret>}</select>
                </mapper>
                """);

        Object[] variants = reference().getVariants();

        assertTrue(Arrays.stream(variants).anyMatch(variant -> variant.toString().contains("name")));
    }

    public void testDumbModeIsUnresolvedAndCancellationPropagates() throws Throwable {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where name = #{user.na<caret>me}</select>
                </mapper>
                """);
        PsiReference reference = reference();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertNull(reference.resolve()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return ((PsiPolyVariantReference) reference).multiResolve(false);
                    },
                    indicator);
            fail("取消后的参数引用解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能降级成未解析引用。
        }
    }

    private void addMapper() {
        myFixture.addFileToProject(
                "src/main/java/com/example/UserMapper.java",
                """
                        package com.example;
                        import org.apache.ibatis.annotations.Param;
                        public interface UserMapper {
                            Object find(@Param("user") User user,
                                        @Param("items") java.util.List<Item> items);
                            int insert(@Param("user") User user);
                            Object direct(User user);
                            Object dynamic(java.util.Map<String, Object> values);
                            Object getter(Bean bean);
                            Object array(Item[] values);
                        }
                        final class User {
                            long id;
                            String name;
                        }
                        final class Item {
                            String code;
                        }
                        final class Bean {
                            private String label;
                            public String getLabel() { return label; }
                        }
                        """);
    }

    private void configure(String xml) {
        myFixture.configureByText("UserMapper.xml", xml);
    }

    private PsiReference reference() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        assertNotNull(reference);
        assertTrue(reference instanceof MyBatisParameterReference);
        return reference;
    }

    private void moveCaretTo(String needle) {
        String text = myFixture.getFile().getText();
        int offset = text.indexOf(needle);
        assertTrue("测试文本中必须存在定位内容：" + needle, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset + 1);
    }

    private void assertField(PsiElement target, String name) {
        assertTrue("参数属性应解析到字段，实际目标：" + target, target instanceof PsiField);
        assertEquals(name, ((PsiField) target).getName());
    }
}
