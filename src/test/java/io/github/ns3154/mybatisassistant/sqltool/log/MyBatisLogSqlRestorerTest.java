package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class MyBatisLogSqlRestorerTest extends BasePlatformTestCase {
    public void testRestoresNumbersStringsNullAndBoolean() {
        MyBatisLogRestoreReport report = restore("""
                ==>  Preparing: SELECT * FROM users WHERE id = ? AND name = ? AND note IS ? AND active = ?
                ==> Parameters: 7(Long), O'Brien(String), null, true(Boolean)
                """);

        assertEmpty(report.diagnostics());
        MyBatisRestoredStatement statement = assertOneElement(report.statements());
        assertEquals(
                "SELECT * FROM users WHERE id = 7 AND name = 'O''Brien' "
                        + "AND note IS NULL AND active = TRUE",
                statement.sql());
        assertEquals(MyBatisSqlRisk.READ_ONLY, statement.riskAssessment().risk());
        assertFalse(statement.riskAssessment().confirmationRequired());
    }

    public void testPreservesCommaParenthesesJsonAndTypedTimeAsQuotedValues() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: INSERT INTO audit(payload, status, happened_at) VALUES (?, ?, ?)
                ==> Parameters: {"message":"a, b(c)"}(String), DONE(Status), 2026-08-12T10:11:12(LocalDateTime)
                """);

        assertEmpty(report.diagnostics());
        MyBatisRestoredStatement statement = assertOneElement(report.statements());
        assertEquals(
                "INSERT INTO audit(payload, status, happened_at) VALUES "
                        + "('{\"message\":\"a, b(c)\"}', 'DONE', '2026-08-12T10:11:12')",
                statement.sql());
        assertEquals(MyBatisSqlRisk.WRITE, statement.riskAssessment().risk());
        assertTrue(statement.riskAssessment().confirmationRequired());
    }

    public void testReplacesOnlyRealJdbcPlaceholders() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT '?', "?", `?`, [?], $$?$$, ? /* ? */ -- ?
                ==> Parameters: value(String)
                """);

        assertEmpty(report.diagnostics());
        assertEquals(
                "SELECT '?', \"?\", `?`, [?], $$?$$, 'value' /* ? */ -- ?",
                assertOneElement(report.statements()).sql());
    }

    public void testIgnoresNestedCommentsAndBackslashEscapedQuotedText() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT /* outer ? /* nested ? */ still ? */ "a\\\"?", `b\\`?`, ?
                ==> Parameters: safe(String)
                """);

        assertEmpty(report.diagnostics());
        assertEquals(
                "SELECT /* outer ? /* nested ? */ still ? */ \"a\\\"?\", `b\\`?`, 'safe'",
                assertOneElement(report.statements()).sql());
    }

    public void testCorrelatesInterleavedThreadContexts() {
        MyBatisLogRestoreReport report = restore("""
                2026-08-12 10:00:00.001 DEBUG [worker-1] mapper - ==> Preparing: SELECT * FROM users WHERE id = ?
                2026-08-12 10:00:00.002 DEBUG [worker-2] mapper - ==> Preparing: DELETE FROM jobs WHERE id = ?
                2026-08-12 10:00:00.003 DEBUG [worker-2] mapper - ==> Parameters: 9(Long)
                2026-08-12 10:00:00.004 DEBUG [worker-1] mapper - ==> Parameters: 7(Long)
                """);

        assertEmpty(report.diagnostics());
        assertEquals(2, report.statements().size());
        assertEquals("worker-2", report.statements().get(0).context());
        assertEquals("DELETE FROM jobs WHERE id = 9", report.statements().get(0).sql());
        assertEquals("worker-1", report.statements().get(1).context());
        assertEquals("SELECT * FROM users WHERE id = 7", report.statements().get(1).sql());
    }

    public void testUsesFifoWithinOneContext() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT * FROM users WHERE id = ?
                ==> Preparing: SELECT * FROM roles WHERE id = ?
                ==> Parameters: 1(Long)
                ==> Parameters: 2(Long)
                """);

        assertEmpty(report.diagnostics());
        assertEquals(List.of(
                        "SELECT * FROM users WHERE id = 1",
                        "SELECT * FROM roles WHERE id = 2"),
                report.statements().stream().map(MyBatisRestoredStatement::sql).toList());
    }

    public void testAllowsEmptyParameterListForSqlWithoutPlaceholders() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT CURRENT_TIMESTAMP
                ==> Parameters:
                """);

        assertEmpty(report.diagnostics());
        assertEquals("SELECT CURRENT_TIMESTAMP", assertOneElement(report.statements()).sql());
    }

    public void testRejectsAmbiguousParameterSegmentationWithoutPartialSql() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT * FROM sample WHERE first = ? AND second = ?
                ==> Parameters: a(String), b(String), c(String)
                """);

        assertEmpty(report.statements());
        assertEquals(MyBatisLogDiagnosticCode.AMBIGUOUS_PARAMETERS,
                assertOneElement(report.diagnostics()).code());
    }

    public void testRejectsPlaceholderMismatchAndBinaryValueWithoutPartialSql() {
        MyBatisLogRestoreReport mismatch = restore("""
                ==> Preparing: SELECT * FROM sample WHERE id = ? AND name = ?
                ==> Parameters: 1(Long)
                """);
        MyBatisLogRestoreReport binary = restore("""
                ==> Preparing: INSERT INTO files(id, payload) VALUES (?, ?)
                ==> Parameters: 1(Long), [1, 2, 3](byte[])
                """);

        assertEmpty(mismatch.statements());
        assertEquals(MyBatisLogDiagnosticCode.PLACEHOLDER_COUNT_MISMATCH,
                assertOneElement(mismatch.diagnostics()).code());
        assertEmpty(binary.statements());
        assertEquals(MyBatisLogDiagnosticCode.UNSUPPORTED_BINARY_PARAMETER,
                assertOneElement(binary.diagnostics()).code());
    }

    public void testRejectsMalformedSqlWithoutPartialResult() {
        MyBatisLogRestoreReport report = restore("""
                ==> Preparing: SELECT * FROM sample WHERE name = 'unterminated ?
                ==> Parameters: value(String)
                """);

        assertEmpty(report.statements());
        assertEquals(MyBatisLogDiagnosticCode.MALFORMED_SQL,
                assertOneElement(report.diagnostics()).code());
    }

    public void testReportsUnmatchedPreparingAndParameters() {
        MyBatisLogRestoreReport report = restore("""
                DEBUG [first] mapper - ==> Preparing: SELECT ?
                DEBUG [second] mapper - ==> Parameters: 1(Long)
                """);

        assertEmpty(report.statements());
        assertEquals(2, report.diagnostics().size());
        assertEquals(MyBatisLogDiagnosticCode.PARAMETERS_WITHOUT_PREPARING,
                report.diagnostics().get(0).code());
        assertEquals(MyBatisLogDiagnosticCode.PREPARING_WITHOUT_PARAMETERS,
                report.diagnostics().get(1).code());
        assertEquals(2, report.diagnostics().get(0).lineNumber());
        assertEquals(1, report.diagnostics().get(1).lineNumber());
    }

    public void testRejectsOversizedInputBeforeParsing() {
        MyBatisLogRestoreReport report = restore("x".repeat(
                MyBatisLogSqlRestorer.MAX_INPUT_BYTES + 1));

        assertEmpty(report.statements());
        assertEquals(MyBatisLogDiagnosticCode.INPUT_TOO_LARGE,
                assertOneElement(report.diagnostics()).code());
    }

    public void testAppliesUtf8ByteLimitToNonAsciiInput() {
        MyBatisLogRestoreReport report = restore("中".repeat(
                MyBatisLogSqlRestorer.MAX_INPUT_BYTES / 2));

        assertEmpty(report.statements());
        assertEquals(MyBatisLogDiagnosticCode.INPUT_TOO_LARGE,
                assertOneElement(report.diagnostics()).code());
    }

    public void testFormatterIncludesRiskAndDiagnosticsButNotEmptyArtifacts() {
        String restored = MyBatisLogRestoreFormatter.format(restore("""
                ==> Preparing: UPDATE users SET name = ? WHERE id = ?
                ==> Parameters: Alice(String), 7(Long)
                """));
        String empty = MyBatisLogRestoreFormatter.format(restore("普通应用日志"));

        assertTrue(restored.contains("风险：写入"));
        assertTrue(restored.contains("后续执行需确认：是"));
        assertTrue(restored.contains("UPDATE users SET name = 'Alice' WHERE id = 7"));
        assertEquals("未发现可还原的 MyBatis Preparing/Parameters 日志。", empty);
    }

    private static MyBatisLogRestoreReport restore(String text) {
        return MyBatisLogSqlRestorer.restore(text.stripTrailing());
    }
}
