package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisSelectArtifactConverterTest extends BasePlatformTestCase {
    public void testConvertsExplicitSelectIntoFourPreviewArtifacts() {
        MyBatisSelectArtifactConversionResult result = MyBatisSelectArtifactConverter.convert(
                """
                        SELECT u.id,
                               u.display_name,
                               COUNT(o.id) AS order_count
                        FROM users u
                        LEFT JOIN orders o ON o.user_id = u.id
                        WHERE u.id = ? AND u.score < 100 AND u.note = '?'
                        GROUP BY u.id, u.display_name;
                        """,
                "com.example.account",
                "UserQueryMapper",
                "findUsers");

        MyBatisSelectArtifactConversionResult.Success success =
                assertInstanceOf(result, MyBatisSelectArtifactConversionResult.Success.class);
        assertTrue(success.mapperSource().contains(
                "List<UserQueryRow> findUsers(@Param(\"param1\") Object param1);"));
        assertTrue(success.xmlSource().contains(
                "<resultMap id=\"findUsersResultMap\""));
        assertTrue(success.xmlSource().contains(
                "column=\"order_count\" property=\"orderCount\" jdbcType=\"OTHER\""));
        assertTrue(success.xmlSource().contains(
                "u.id = #{param1,jdbcType=OTHER}"));
        assertTrue(success.xmlSource().contains("u.score &lt; 100"));
        assertTrue(success.xmlSource().contains("u.note = '?'"));
        assertTrue(success.rowModelSource().contains("private Object displayName;"));
        assertTrue(success.rowModelSource().contains("public void setOrderCount(Object value)"));
        assertSize(1, success.warnings());
    }

    public void testSupportsQuotedColumnAndExpressionAlias() {
        MyBatisSelectArtifactConversionResult result = MyBatisSelectArtifactConverter.convert(
                "SELECT \"display name\", COALESCE(score, 0) AS total_score FROM users",
                "com.example",
                "ReportMapper",
                "load");

        MyBatisSelectArtifactConversionResult.Success success =
                assertInstanceOf(result, MyBatisSelectArtifactConversionResult.Success.class);
        assertTrue(success.xmlSource().contains(
                "column=\"display name\" property=\"displayName\""));
        assertTrue(success.rowModelSource().contains("private Object totalScore;"));
        assertFalse(success.mapperSource().contains("@Param"));
    }

    public void testRejectsWildcardAmbiguousProjectionAndDuplicateProperty() {
        assertFailure("SELECT * FROM users", "不支持 *");
        assertFailure("SELECT COUNT(id) FROM users", "必须使用 AS");
        assertFailure("SELECT id AS user_id, name AS user_id FROM users", "投影属性重复");
    }

    public void testRejectsWriteMultipleMalformedAndInvalidNames() {
        assertFailure("DELETE FROM users", "仅支持单条");
        assertFailure("SELECT id FROM users; SELECT name FROM users", "仅支持单条");
        assertFailure("SELECT 'broken FROM users", "仅支持单条");
        MyBatisSelectArtifactConversionResult invalidName =
                MyBatisSelectArtifactConverter.convert(
                        "SELECT id FROM users", "bad-package", "Mapper", "find");
        assertTrue(invalidName instanceof MyBatisSelectArtifactConversionResult.Failure);
        assertFailure("SELECT " + "x".repeat(
                MyBatisSelectArtifactConverter.MAX_INPUT_BYTES) + " FROM users", "超过");
    }

    public void testRejectsPlaceholderMismatchOnlyThroughSafeRewriter() {
        var rewritten = io.github.ns3154.mybatisassistant.sqltool.log
                .MyBatisJdbcPlaceholderRewriter.rewrite(
                        "SELECT ? AS value FROM users WHERE note = '?' AND body = $$?$$",
                        java.util.List.of("#{param1}"));
        assertInstanceOf(rewritten,
                io.github.ns3154.mybatisassistant.sqltool.log
                        .MyBatisJdbcPlaceholderRewriteResult.Success.class);
        var mismatch = io.github.ns3154.mybatisassistant.sqltool.log
                .MyBatisJdbcPlaceholderRewriter.rewrite("SELECT ? FROM users", java.util.List.of());
        assertInstanceOf(mismatch,
                io.github.ns3154.mybatisassistant.sqltool.log
                        .MyBatisJdbcPlaceholderRewriteResult.Failure.class);
    }

    private static void assertFailure(String sql, String messagePart) {
        MyBatisSelectArtifactConversionResult result = MyBatisSelectArtifactConverter.convert(
                sql, "com.example", "UserMapper", "find");
        MyBatisSelectArtifactConversionResult.Failure failure =
                assertInstanceOf(result, MyBatisSelectArtifactConversionResult.Failure.class);
        assertTrue(failure.message(), failure.message().contains(messagePart));
    }
}
