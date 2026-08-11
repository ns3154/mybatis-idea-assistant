package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbol;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;

import java.util.Set;

public final class MyBatisXmlSymbolScannerTest extends BasePlatformTestCase {
    public void testScansAllDirectMapperSymbols() {
        Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace=" com.example.UserMapper ">
                    <resultMap id="userMap" type="com.example.User"/>
                    <sql id="columns">id, name</sql>
                    <select id="findOne">select 1</select>
                    <insert id="insertOne">insert into sample values (1)</insert>
                    <update id="updateOne">update sample set id = 1</update>
                    <delete id="deleteOne">delete from sample</delete>
                </mapper>
                """);

        assertEquals(7, symbols.size());
        assertTrue(symbols.contains(MyBatisXmlSymbol.namespace("com.example.UserMapper")));
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.RESULT_MAP,
                "com.example.UserMapper",
                "userMap")));
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.SQL_FRAGMENT,
                "com.example.UserMapper",
                "columns")));
        for (String id : Set.of("findOne", "insertOne", "updateOne", "deleteOne")) {
            assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                    MyBatisXmlSymbolKind.STATEMENT,
                    "com.example.UserMapper",
                    id)));
        }
    }

    public void testDeduplicatesKeysButPreservesKinds() {
        Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan("""
                <mapper namespace="com.example.UserMapper">
                    <select id="shared">select 1</select>
                    <select id="shared">select 2</select>
                    <resultMap id="shared" type="java.lang.Object"/>
                    <sql id="shared">id</sql>
                </mapper>
                """);

        assertEquals(4, symbols.size());
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.UserMapper",
                "shared")));
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.RESULT_MAP,
                "com.example.UserMapper",
                "shared")));
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.SQL_FRAGMENT,
                "com.example.UserMapper",
                "shared")));
    }

    public void testRejectsMalformedOrUnrelatedXml() {
        assertEmpty(MyBatisXmlSymbolScanner.scan("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findOne">select 1
                """));
        assertEmpty(MyBatisXmlSymbolScanner.scan("""
                <mapper namespace="com.example.UserMapper"/>
                <mapper namespace="com.example.OtherMapper"/>
                """));
        assertEmpty(MyBatisXmlSymbolScanner.scan("""
                <mapper namespace="com.example.UserMapper">
                    <select id="findOne"></insert>
                </mapper>
                """));
        assertEmpty(MyBatisXmlSymbolScanner.scan("""
                <beans>
                    <mapper namespace="com.example.UserMapper"/>
                </beans>
                """));
    }

    public void testIgnoresBlankAndPrefixedAttributesAndNestedSymbols() {
        Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan("""
                <mapper xmlns:x="urn:test" namespace="com.example.UserMapper">
                    <select id="  ">select 1</select>
                    <select x:id="prefixed">select 2</select>
                    <x:select id="prefixedTag">select 3</x:select>
                    <select id="outer">
                        <sql id="nested">id</sql>
                    </select>
                </mapper>
                """);

        assertEquals(Set.of(
                MyBatisXmlSymbol.namespace("com.example.UserMapper"),
                MyBatisXmlSymbol.named(
                        MyBatisXmlSymbolKind.STATEMENT,
                        "com.example.UserMapper",
                        "outer")), symbols);
    }

    public void testDoesNotResolveInternalOrExternalEntities() {
        Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan("""
                <!DOCTYPE mapper [
                    <!ENTITY internal "leakedInternal">
                    <!ENTITY external SYSTEM "file:///definitely-not-readable/mybatis-assistant-secret">
                ]>
                <mapper namespace="com.example.UserMapper">
                    <sql id="&internal;">id</sql>
                    <sql id="&external;">name</sql>
                </mapper>
                """);

        assertEquals(Set.of(MyBatisXmlSymbol.namespace("com.example.UserMapper")), symbols);
    }

    public void testScansOneHundredThousandStatementsLinearly() {
        int statementCount = 100_000;
        StringBuilder xml = new StringBuilder(statementCount * 28);
        xml.append("<mapper namespace=\"com.example.HugeMapper\">");
        for (int index = 0; index < statementCount; index++) {
            xml.append("<select id=\"s").append(index).append("\"/>");
        }
        xml.append("</mapper>");

        Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan(xml);

        assertEquals(statementCount + 1, symbols.size());
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.HugeMapper",
                "s0")));
        assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                MyBatisXmlSymbolKind.STATEMENT,
                "com.example.HugeMapper",
                "s99999")));
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return MyBatisXmlSymbolScanner.scan("<mapper namespace=\"x\"/>");
                    },
                    indicator);
            fail("取消后的 XML 扫描必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，扫描器必须向上传播。
        }
    }
}
