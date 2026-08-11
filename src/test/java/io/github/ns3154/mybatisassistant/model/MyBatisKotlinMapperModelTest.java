package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisKotlinMapperModelTest extends BasePlatformTestCase {
    public void testBuildsKotlinK2MapperAndInheritedGenericMethodsThroughLightClass() {
        myFixture.addFileToProject("src/main/kotlin/com/example/KotlinUserMapper.kt", """
                package com.example

                interface ParentMapper<T, ID> {
                    fun find(id: ID): T
                }

                interface KotlinUserMapper : ParentMapper<KotlinUser, Long> {
                    fun findByName(name: String): KotlinUser?
                }

                data class KotlinUser(val id: Long, val name: String)
                """);
        myFixture.addFileToProject("src/main/resources/mapper/KotlinUserMapper.xml", """
                <mapper namespace="com.example.KotlinUserMapper">
                    <select id="find">select 1</select>
                    <select id="findByName">select 1</select>
                </mapper>
                """);
        PsiClass mapper = findClass("com.example.KotlinUserMapper");
        assertNotNull(mapper);

        MyBatisMapperModelResolution resolution = ReadAction.compute(
                () -> MyBatisMapperModelResolver.resolve(mapper));

        assertInstanceOf(resolution, MyBatisMapperModelResolution.Found.class);
        MyBatisMapperModel model = ((MyBatisMapperModelResolution.Found) resolution).model();
        assertEquals("com.example.KotlinUserMapper", model.qualifiedName());
        MyBatisMapperMethodModel inherited = method(model, "find");
        assertTrue(inherited.inherited());
        assertEquals("com.example.KotlinUser", inherited.returnType());
        assertEquals("java.lang.Long", inherited.parameters().getFirst().canonicalType());
        MyBatisMapperMethodModel declared = method(model, "findByName");
        assertFalse(declared.inherited());
        assertEquals("com.example.KotlinUser", declared.returnType());
        assertEquals(MyBatisEntityKind.CLASS, declared.returnEntity().kind());
        assertEquals("com.example.KotlinUser", declared.returnEntity().qualifiedName());
        assertTrue(declared.returnEntity().nullable());
        assertEquals("java.lang.String", declared.parameters().getFirst().canonicalType());
    }

    public void testRecognizesKotlinMapperAndSqlAnnotations() {
        myFixture.addFileToProject("src/main/kotlin/org/apache/ibatis/annotations/Mapper.kt", """
                package org.apache.ibatis.annotations
                annotation class Mapper
                """);
        myFixture.addFileToProject("src/main/kotlin/org/apache/ibatis/annotations/Select.kt", """
                package org.apache.ibatis.annotations
                annotation class Select(vararg val value: String)
                """);
        myFixture.addFileToProject("src/main/kotlin/com/example/AnnotatedKotlinMapper.kt", """
                package com.example

                import org.apache.ibatis.annotations.Mapper
                import org.apache.ibatis.annotations.Select

                @Mapper
                interface AnnotatedKotlinMapper {
                    @Select("select 1")
                    fun count(): Int
                }
                """);
        PsiClass mapper = findClass("com.example.AnnotatedKotlinMapper");
        assertNotNull(mapper);
        assertEquals(
                java.util.List.of("org.apache.ibatis.annotations.Mapper"),
                ReadAction.compute(() -> java.util.Arrays.stream(mapper.getAnnotations())
                        .map(annotation -> annotation.getQualifiedName())
                        .toList()));

        MyBatisMapperModelResolution resolution = ReadAction.compute(
                () -> MyBatisMapperModelResolver.resolve(mapper));

        assertInstanceOf(resolution, MyBatisMapperModelResolution.Found.class);
        MyBatisMapperMethodModel count = method(
                ((MyBatisMapperModelResolution.Found) resolution).model(),
                "count");
        assertEquals(MyBatisStatementSourceKind.ANNOTATION_SQL, count.statementSource());
    }

    private PsiClass findClass(String qualifiedName) {
        return ReadAction.compute(() -> JavaPsiFacade.getInstance(getProject()).findClass(
                qualifiedName,
                GlobalSearchScope.projectScope(getProject())));
    }

    private MyBatisMapperMethodModel method(MyBatisMapperModel model, String name) {
        return model.methods().stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 Kotlin Mapper 方法：" + name));
    }
}
