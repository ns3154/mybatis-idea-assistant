package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisOgnlKotlinSemanticTest extends BasePlatformTestCase {
    public void testKotlinLightTypesProvidePropertiesAndGenericCollectionElements() {
        myFixture.addFileToProject("src/main/kotlin/com/example/KotlinUserMapper.kt", """
                package com.example

                interface KotlinUserMapper {
                    fun find(query: KotlinQuery): Any
                }

                data class KotlinQuery(
                    val name: String,
                    val users: List<KotlinUser>
                )

                data class KotlinUser(val age: Int)
                """);
        XmlFile xml = (XmlFile) myFixture.configureByText("KotlinUserMapper.xml", """
                <mapper namespace="com.example.KotlinUserMapper">
                    <select id="find">
                        <if test="name != null and users[0].age > 0">x</if>
                    </select>
                </mapper>
                """);
        XmlAttributeValue source = PsiTreeUtil.findChildrenOfType(
                xml,
                XmlAttribute.class).stream()
                .filter(attribute -> "test".equals(attribute.getName()))
                .map(XmlAttribute::getValueElement)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();

        MyBatisOgnlSemanticModel model = MyBatisOgnlSemanticAnalyzer.analyze(source);

        assertEquals(MyBatisOgnlSemanticStatus.FOUND, model.rootResult().status());
        assertType(model, "name", "java.lang.String");
        assertType(model, "users", "java.util.List<com.example.KotlinUser>");
        assertType(model, "age", "int");
    }

    private static void assertType(
            MyBatisOgnlSemanticModel model,
            String name,
            String expectedType) {
        MyBatisOgnlOccurrence occurrence = model.occurrences().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 OGNL occurrence：" + name));
        assertContainsElements(
                occurrence.result().types().stream().map(PsiType::getCanonicalText).toList(),
                expectedType);
    }
}
