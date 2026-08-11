package io.github.ns3154.mybatisassistant.sqltool.format;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisXmlFormatterTest extends BasePlatformTestCase {
    public void testFormatsMapperStructureAndDynamicTags() {
        String source = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                <select id="find" resultType="User">
                SELECT *
                FROM users
                <where>
                <if test="name != null">
                AND name = #{name}
                </if>
                </where>
                </select>
                </mapper>
                """;

        assertEquals("""
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                  <select id="find" resultType="User">
                    SELECT *
                    FROM users
                    <where>
                      <if test="name != null">
                        AND name = #{name}
                      </if>
                    </where>
                  </select>
                </mapper>
                """, success(source));
    }

    public void testPreservesCdataCommentContentsBlankLinesAndLineEndings() {
        String source = "<mapper namespace=\"x.Mapper\">\r\n"
                + "<!-- note\r\n"
                + "    keep this comment indentation\r\n"
                + "-->\r\n"
                + "<select id=\"find\">\r\n"
                + "<![CDATA[\r\n"
                + "  SELECT *\r\n"
                + "    WHERE value < 10\r\n"
                + "]]>\r\n"
                + "\r\n"
                + "</select>\r\n"
                + "</mapper>\r\n";

        String formatted = success(source);

        assertTrue(formatted.contains("    keep this comment indentation\r\n"));
        assertTrue(formatted.contains("  SELECT *\r\n    WHERE value < 10\r\n"));
        assertTrue(formatted.contains("  <select id=\"find\">\r\n"));
        assertTrue(formatted.endsWith("</mapper>\r\n"));
        assertFalse(formatted.replace("\r\n", "").contains("\n"));
    }

    public void testPreservesMultilineTagInteriorAndSupportsSelfClosingTags() {
        String source = """
                <mapper
                      namespace="x.Mapper">
                <resultMap id="user"
                        type="x.User">
                <id property="id" column="id" />
                </resultMap>
                </mapper>
                """;

        String formatted = success(source);

        assertTrue(formatted.contains("<mapper\n      namespace=\"x.Mapper\">"));
        assertTrue(formatted.contains("  <resultMap id=\"user\"\n        type=\"x.User\">"));
        assertTrue(formatted.contains("    <id property=\"id\" column=\"id\" />"));
    }

    public void testFormattingIsIdempotent() {
        String source = """
                <mapper namespace="x.Mapper">
                <select id="find">
                SELECT * FROM users
                <if test="active">WHERE active = 1</if>
                </select>
                </mapper>
                """;

        String once = success(source);
        String twice = success(once);

        assertEquals(once, twice);
    }

    public void testRejectsMismatchedAndUnclosedStructuresWithoutPartialText() {
        MyBatisXmlFormatResult mismatch = MyBatisXmlFormatter.format(
                "<mapper><select></mapper>", 2);
        MyBatisXmlFormatResult unclosedCdata = MyBatisXmlFormatter.format(
                "<mapper><![CDATA[ SELECT ? </mapper>", 2);

        assertFailure(mismatch, MyBatisXmlFormatDiagnosticCode.MALFORMED_XML);
        assertFailure(unclosedCdata, MyBatisXmlFormatDiagnosticCode.MALFORMED_XML);
    }

    public void testRejectsInvalidIndentAndUtf8Oversize() {
        assertFailure(MyBatisXmlFormatter.format("<mapper/>", 0),
                MyBatisXmlFormatDiagnosticCode.INVALID_INDENT_SIZE);
        assertFailure(MyBatisXmlFormatter.format(
                        "中".repeat(MyBatisXmlFormatter.MAX_INPUT_BYTES / 2), 2),
                MyBatisXmlFormatDiagnosticCode.INPUT_TOO_LARGE);
    }

    private static String success(String source) {
        MyBatisXmlFormatResult result = MyBatisXmlFormatter.format(source, 2);
        assertInstanceOf(result, MyBatisXmlFormatResult.Success.class);
        return ((MyBatisXmlFormatResult.Success) result).text();
    }

    private static void assertFailure(
            MyBatisXmlFormatResult result,
            MyBatisXmlFormatDiagnosticCode code) {
        assertInstanceOf(result, MyBatisXmlFormatResult.Failure.class);
        MyBatisXmlFormatResult.Failure failure = (MyBatisXmlFormatResult.Failure) result;
        assertEquals(code, failure.code());
        assertFalse(failure.message().isBlank());
    }
}
