package io.github.ns3154.mybatisassistant.sqltool.execution;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;

public final class MyBatisSqlParameterPanelParserTest extends BasePlatformTestCase {
    public void testParsesAllSupportedTypesAndPreservesStringContent() {
        MyBatisSqlParameterParseResult.Success success = success("""
                STRING: Alice:admin, O'Brien
                LONG: 42
                DECIMAL: 19.95
                BOOLEAN: true
                DATE: 2026-08-12
                TIMESTAMP: 2026-08-12T10:11:12
                NULL: VARCHAR
                """);

        assertEquals(7, success.parameters().size());
        assertEquals(" Alice:admin, O'Brien", success.parameters().get(0).value());
        assertEquals(42L, success.parameters().get(1).value());
        assertEquals(new BigDecimal("19.95"), success.parameters().get(2).value());
        assertEquals(Boolean.TRUE, success.parameters().get(3).value());
        assertEquals(Date.valueOf("2026-08-12"), success.parameters().get(4).value());
        assertEquals(Timestamp.valueOf("2026-08-12 10:11:12"),
                success.parameters().get(5).value());
        assertNull(success.parameters().get(6).value());
        assertEquals(Types.VARCHAR, success.parameters().get(6).jdbcType());
    }

    public void testRejectsInvalidSyntaxValuesBinaryAndExcessCount() {
        assertFailure("missing separator", 1);
        assertFailure("LONG:not-a-number", 1);
        assertFailure("BOOLEAN:yes", 1);
        assertFailure("DATE:2026-13-40", 1);
        assertFailure("TIMESTAMP:today", 1);
        assertFailure("NULL:UNKNOWN_TYPE", 1);
        assertFailure("BINARY:0101", 1);
        assertFailure("UNKNOWN:value", 1);
        assertFailure("STRING:x\n".repeat(
                MyBatisSqlParameterPanelParser.MAX_PARAMETERS + 1),
                MyBatisSqlParameterPanelParser.MAX_PARAMETERS + 1);
    }

    public void testToStringAlwaysRedactsValue() {
        MyBatisSqlParameter parameter = success("STRING:secret-value")
                .parameters().getFirst();

        assertFalse(parameter.toString().contains("secret-value"));
        assertTrue(parameter.toString().contains("已脱敏"));
    }

    private static MyBatisSqlParameterParseResult.Success success(String panel) {
        MyBatisSqlParameterParseResult result = MyBatisSqlParameterPanelParser.parse(panel);
        assertInstanceOf(result, MyBatisSqlParameterParseResult.Success.class);
        return (MyBatisSqlParameterParseResult.Success) result;
    }

    private static void assertFailure(String panel, int line) {
        MyBatisSqlParameterParseResult result = MyBatisSqlParameterPanelParser.parse(panel);
        assertInstanceOf(result, MyBatisSqlParameterParseResult.Failure.class);
        MyBatisSqlParameterParseResult.Failure failure =
                (MyBatisSqlParameterParseResult.Failure) result;
        assertEquals(line, failure.lineNumber());
        assertFalse(failure.message().isBlank());
    }
}
