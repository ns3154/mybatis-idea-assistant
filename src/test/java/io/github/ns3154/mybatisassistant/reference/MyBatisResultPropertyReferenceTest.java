package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;

public final class MyBatisResultPropertyReferenceTest extends BasePlatformTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("src/main/java/com/example/Domain.java", """
                package com.example;
                final class User {
                    private String name;
                    private Address address;
                    private java.util.List<Order> orders;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public void setAddress(Address address) { this.address = address; }
                    public void setOrders(java.util.List<Order> orders) { this.orders = orders; }
                }
                final class Address {
                    private String city;
                    public void setCity(String city) { this.city = city; }
                }
                final class Order {
                    private long total;
                    public void setTotal(long total) { this.total = total; }
                }
                final class Account {
                    private String label;
                    public void setLabel(String label) { this.label = label; }
                }
                final class ImmutableUser {
                    private final String name;
                    private final Address address;
                    ImmutableUser(String name, Address address) {
                        this.name = name;
                        this.address = address;
                    }
                }
                """);
        myFixture.addFileToProject("src/main/resources/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Person" type="com.example.User"/>
                    <typeAlias alias="AddressAlias" type="com.example.Address"/>
                    <typeAlias alias="AccountAlias" type="com.example.Account"/>
                    <typeAlias alias="DynamicMap" type="java.util.Map"/>
                    <typeAlias alias="Immutable" type="com.example.ImmutableUser"/>
                </typeAliases></configuration>
                """);
    }

    public void testRootPropertyPrefersSetterAndSupportsDotPath() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <result property="na<caret>me" column="name"/>
                        <result property="address.city" column="city"/>
                    </resultMap>
                </mapper>
                """);
        assertMethod(reference().resolve(), "setName");

        moveCaretTo("address.city", "city");
        assertMethod(reference().resolve(), "setCity");
    }

    public void testAssociationAndCollectionInferNestedWritableTypes() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <association property="address" columnPrefix="address_">
                            <result property="ci<caret>ty" column="city"/>
                        </association>
                        <collection property="orders">
                            <result property="total" column="total"/>
                        </collection>
                    </resultMap>
                </mapper>
                """);
        assertMethod(reference().resolve(), "setCity");

        moveCaretTo("property=\"total\"", "total");
        assertMethod(reference().resolve(), "setTotal");

        moveCaretTo("columnPrefix=\"address_\"", "address_");
        assertNull(myFixture.getReferenceAtCaretPosition());
    }

    public void testExplicitNestedTypesAndInheritedResultMapTypeResolve() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="baseMap" type="Person"/>
                    <resultMap id="childMap" extends="baseMap">
                        <result property="na<caret>me" column="name"/>
                        <association property="address" javaType="AccountAlias">
                            <result property="label" column="label"/>
                        </association>
                        <collection property="orders" ofType="AccountAlias">
                            <result property="label" column="label"/>
                        </collection>
                    </resultMap>
                </mapper>
                """);
        assertMethod(reference().resolve(), "setName");

        moveCaretTo("property=\"label\"", "label");
        assertMethod(reference().resolve(), "setLabel");

        String text = myFixture.getFile().getText();
        int secondLabel = text.lastIndexOf("property=\"label\"");
        myFixture.getEditor().getCaretModel().moveToOffset(secondLabel + "property=\"".length() + 2);
        assertMethod(reference().resolve(), "setLabel");
    }

    public void testDiscriminatorCaseResultTypeChangesNestedPropertyOwner() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <discriminator column="kind" javaType="string">
                            <case value="account" resultType="AccountAlias">
                                <result property="la<caret>bel" column="label"/>
                            </case>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);

        assertMethod(reference().resolve(), "setLabel");
    }

    public void testIndexedPropertyAndDiscriminatorResultMapBranchResolve() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="accountMap" type="AccountAlias"/>
                    <resultMap id="userMap" type="Person">
                        <result property="orders[0].to<caret>tal" column="total"/>
                        <discriminator column="kind" javaType="string">
                            <case value="account" resultMap="accountMap">
                                <result property="label" column="label"/>
                            </case>
                        </discriminator>
                    </resultMap>
                </mapper>
                """);
        assertMethod(reference().resolve(), "setTotal");

        moveCaretTo("property=\"label\"", "label");
        assertMethod(reference().resolve(), "setLabel");
    }

    public void testCompletionMissingAndDynamicMapBoundaries() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <result property="na<caret>me" column="name"/>
                        <result property="missing" column="missing"/>
                    </resultMap>
                </mapper>
                """);
        Object[] variants = reference().getVariants();
        assertTrue(Arrays.stream(variants).anyMatch(variant -> variant.toString().contains("name")));

        moveCaretTo("property=\"missing\"", "missing");
        MyBatisResultPropertyReference missing = (MyBatisResultPropertyReference) reference();
        assertNull(missing.resolve());
        assertTrue(missing.isDefinitelyMissing());

        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="map" type="DynamicMap">
                        <result property="unknown<caret>Key" column="value"/>
                    </resultMap>
                </mapper>
                """);
        MyBatisResultPropertyReference map = (MyBatisResultPropertyReference) reference();
        assertNull(map.resolve());
        assertFalse(map.isDefinitelyMissing());
    }

    public void testConstructorArgumentNavigatesToJavaParameterAndCompletesNames() {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="immutableMap" type="Immutable">
                        <constructor>
                            <idArg name="na<caret>me" column="name" javaType="string"/>
                            <arg name="address" column="address" javaType="AddressAlias"/>
                        </constructor>
                    </resultMap>
                </mapper>
                """);

        PsiElement target = reference().resolve();

        assertTrue(target instanceof com.intellij.psi.PsiParameter);
        assertEquals("name", ((com.intellij.psi.PsiParameter) target).getName());
        assertTrue(Arrays.stream(reference().getVariants())
                .anyMatch(variant -> variant.toString().contains("address")));
    }

    public void testDumbModeIsUnresolvedAndCancellationPropagates() throws Throwable {
        configure("""
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="userMap" type="Person">
                        <result property="na<caret>me" column="name"/>
                    </resultMap>
                </mapper>
                """);
        PsiReference reference = reference();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> assertNull(reference.resolve()));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return reference.resolve();
                    },
                    indicator);
            fail("取消后的 ResultMap 属性解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能伪装成未解析属性。
        }
    }

    private void configure(String xml) {
        myFixture.configureByText("UserMapper.xml", xml);
    }

    private PsiReference reference() {
        PsiReference reference = myFixture.getReferenceAtCaretPosition();
        assertNotNull(reference);
        assertTrue(reference instanceof MyBatisResultPropertyReference
                || reference instanceof MyBatisConstructorArgumentReference);
        return reference;
    }

    private void moveCaretTo(String needle, String content) {
        String text = myFixture.getFile().getText();
        int offset = text.indexOf(needle);
        assertTrue("测试文本中必须存在定位内容：" + needle, offset >= 0);
        int contentOffset = needle.indexOf(content);
        myFixture.getEditor().getCaretModel().moveToOffset(
                offset + contentOffset + Math.max(1, content.length() / 2));
    }

    private void assertMethod(PsiElement target, String name) {
        assertTrue("ResultMap 属性应解析到 setter，实际目标：" + target, target instanceof PsiMethod);
        assertEquals(name, ((PsiMethod) target).getName());
    }
}
