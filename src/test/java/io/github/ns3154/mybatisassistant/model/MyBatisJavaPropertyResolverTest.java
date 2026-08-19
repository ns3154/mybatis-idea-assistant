package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisJavaPropertyResolverTest extends BasePlatformTestCase {
    private PsiClass child;
    private PsiClass holder;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("Domain.java", """
                package com.example;
                class Base<T> {
                    private T value;
                    public T getValue() { return value; }
                    public void setValue(T value) { this.value = value; }
                }
                class Child extends Base<String> {
                    public String direct;
                    public boolean isActive() { return true; }
                    public String getURL() { return ""; }
                    public static String getStaticValue() { return ""; }
                    public void getVoid() {}
                    public String getWithArgument(String value) { return value; }
                }
                class Holder {
                    Child child;
                    int primitive;
                    String[] array;
                    java.util.List<String> list;
                    java.util.List rawList;
                    java.util.Map<String, Object> map;
                }
                """);
        child = findClass(file, "Child");
        holder = findClass(file, "Holder");
    }

    public void testGenericInheritedAccessorsUseSubstitutedTypes() {
        PsiType childType = fieldType("child");

        MyBatisJavaPropertyResolution readable = MyBatisJavaPropertyResolver.resolve(
                childType,
                "value",
                MyBatisJavaPropertyAccess.READ);
        MyBatisJavaPropertyResolution writable = MyBatisJavaPropertyResolver.resolve(
                childType,
                "value",
                MyBatisJavaPropertyAccess.WRITE);

        assertMethod(readable.targets().getFirst(), "getValue");
        assertEquals("String", readable.types().getFirst().getPresentableText());
        assertMethod(writable.targets().getFirst(), "setValue");
        assertEquals("String", writable.types().getFirst().getPresentableText());
    }

    public void testBooleanAcronymAndFieldFallbackPropertiesAreModeled() {
        PsiType childType = fieldType("child");

        assertMethod(MyBatisJavaPropertyResolver.resolve(
                childType,
                "active",
                MyBatisJavaPropertyAccess.READ).targets().getFirst(), "isActive");
        assertMethod(MyBatisJavaPropertyResolver.resolve(
                childType,
                "URL",
                MyBatisJavaPropertyAccess.READ).targets().getFirst(), "getURL");
        PsiElement field = MyBatisJavaPropertyResolver.resolve(
                childType,
                "direct",
                MyBatisJavaPropertyAccess.READ).targets().getFirst();
        assertInstanceOf(field, PsiField.class);
        assertEquals("direct", ((PsiField) field).getName());

        List<String> variants = MyBatisJavaPropertyResolver.variants(
                childType,
                MyBatisJavaPropertyAccess.READ);
        assertTrue(variants.containsAll(List.of("active", "URL", "direct")));
        assertEmpty(MyBatisJavaPropertyResolver.resolve(
                childType,
                "staticValue",
                MyBatisJavaPropertyAccess.READ).targets());
        assertEmpty(MyBatisJavaPropertyResolver.resolve(
                childType,
                "void",
                MyBatisJavaPropertyAccess.READ).targets());
        assertEmpty(MyBatisJavaPropertyResolver.resolve(
                childType,
                "withArgument",
                MyBatisJavaPropertyAccess.READ).targets());
    }

    public void testDynamicPrimitiveUnresolvedAndIndexedTypeBoundaries() {
        MyBatisJavaPropertyResolution nullType = MyBatisJavaPropertyResolver.resolve(
                null,
                "value",
                MyBatisJavaPropertyAccess.READ);
        assertTrue(nullType.unknown());

        MyBatisJavaPropertyResolution primitive = MyBatisJavaPropertyResolver.resolve(
                PsiType.INT,
                "value",
                MyBatisJavaPropertyAccess.READ);
        assertFalse(primitive.unknown());
        assertEmpty(primitive.targets());

        PsiType unresolved = JavaPsiFacade.getElementFactory(getProject())
                .createTypeFromText("com.missing.Unknown", holder);
        assertTrue(MyBatisJavaPropertyResolver.resolve(
                unresolved,
                "value",
                MyBatisJavaPropertyAccess.READ).unknown());
        assertEmpty(MyBatisJavaPropertyResolver.variants(
                unresolved,
                MyBatisJavaPropertyAccess.READ));

        assertTrue(MyBatisJavaPropertyResolver.isDynamicMap(fieldType("map")));
        assertFalse(MyBatisJavaPropertyResolver.isDynamicMap(PsiType.INT));
        assertTrue(MyBatisJavaPropertyResolver.resolve(
                fieldType("map"),
                "dynamicKey",
                MyBatisJavaPropertyAccess.READ).unknown());
        assertEmpty(MyBatisJavaPropertyResolver.variants(
                fieldType("map"),
                MyBatisJavaPropertyAccess.READ));

        assertEquals("String", MyBatisJavaPropertyResolver.indexedType(
                fieldType("array")).getPresentableText());
        assertEquals("String", MyBatisJavaPropertyResolver.indexedType(
                fieldType("list")).getPresentableText());
        assertNull(MyBatisJavaPropertyResolver.indexedType(fieldType("rawList")));
        assertNull(MyBatisJavaPropertyResolver.indexedType(fieldType("child")));
        assertNull(MyBatisJavaPropertyResolver.indexedType(PsiType.INT));
    }

    private PsiType fieldType(String name) {
        PsiField field = holder.findFieldByName(name, false);
        assertNotNull(field);
        return field.getType();
    }

    private PsiClass findClass(PsiJavaFile file, String name) {
        for (PsiClass psiClass : file.getClasses()) {
            if (name.equals(psiClass.getName())) {
                return psiClass;
            }
        }
        fail("缺少测试类：" + name);
        throw new AssertionError();
    }

    private void assertMethod(PsiElement element, String name) {
        assertInstanceOf(element, PsiMethod.class);
        assertEquals(name, ((PsiMethod) element).getName());
    }
}
