package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisSqlRiskClassifierTest extends BasePlatformTestCase {
    public void testClassifiesReadWriteDdlAndUnknownConservatively() {
        assertRisk(MyBatisSqlRisk.READ_ONLY, false, "SELECT * FROM users");
        assertRisk(MyBatisSqlRisk.READ_ONLY, false, "/* trace */ SHOW TABLES");
        assertRisk(MyBatisSqlRisk.WRITE, true, "UPDATE users SET active = TRUE");
        assertRisk(MyBatisSqlRisk.DDL, true, "DROP TABLE audit_log");
        assertRisk(MyBatisSqlRisk.UNKNOWN, true,
                "WITH recent AS (SELECT * FROM users) SELECT * FROM recent");
        assertRisk(MyBatisSqlRisk.UNKNOWN, true, "SELECT1 FROM users");
        assertRisk(MyBatisSqlRisk.UNKNOWN, true, "EXPLAIN ANALYZE SELECT * FROM users");
        assertRisk(MyBatisSqlRisk.UNKNOWN, true, "CALL rebuild_index()");
    }

    public void testMarksMultipleReadStatementsForConfirmation() {
        MyBatisSqlRiskAssessment assessment = MyBatisSqlRiskClassifier.assess(
                "SELECT ';' AS value; -- boundary\nSELECT 2");

        assertEquals(MyBatisSqlRisk.READ_ONLY, assessment.risk());
        assertEquals(2, assessment.statementCount());
        assertTrue(assessment.multipleStatements());
        assertTrue(assessment.confirmationRequired());
        assertTrue(assessment.structurallyValid());
    }

    public void testMixedReadAndWriteUsesHigherRisk() {
        MyBatisSqlRiskAssessment write = MyBatisSqlRiskClassifier.assess(
                "SELECT 1; DELETE FROM jobs WHERE id = 1");
        MyBatisSqlRiskAssessment ddl = MyBatisSqlRiskClassifier.assess(
                "SELECT 1; ALTER TABLE users ADD COLUMN note TEXT");

        assertEquals(MyBatisSqlRisk.WRITE, write.risk());
        assertEquals(MyBatisSqlRisk.DDL, ddl.risk());
    }

    public void testMalformedAndEmptySqlAreUnknown() {
        MyBatisSqlRiskAssessment malformed = MyBatisSqlRiskClassifier.assess(
                "SELECT 'unterminated");
        MyBatisSqlRiskAssessment empty = MyBatisSqlRiskClassifier.assess(" -- comment only");

        assertEquals(MyBatisSqlRisk.UNKNOWN, malformed.risk());
        assertFalse(malformed.structurallyValid());
        assertTrue(malformed.confirmationRequired());
        assertEquals(MyBatisSqlRisk.UNKNOWN, empty.risk());
        assertEquals(0, empty.statementCount());
    }

    public void testFindsKeywordOutsideStringsIdentifiersAndComments() {
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.PRESENT,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "UPDATE users SET active = 0 WHERE id = 1", "update"));
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.ABSENT,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "UPDATE users SET note = 'where', `where` = 1 /* WHERE id = 1 */",
                        "update"));
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.ABSENT,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "UPDATE users SET somewhere = 1, where1 = 2, _where = 3, 中文where = 4",
                        "update"));
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.UNCERTAIN,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "UPDATE users SET note = 'unterminated", "update"));
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.UNCERTAIN,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "UPDATE users SET active = 0; DELETE FROM users", "update"));
        assertEquals(
                MyBatisSqlRiskClassifier.SqlKeywordResult.UNCERTAIN,
                MyBatisSqlRiskClassifier.findWhereKeyword(
                        "SELECT * FROM users", "update"));
    }

    private void assertRisk(MyBatisSqlRisk expected, boolean confirmation, String sql) {
        MyBatisSqlRiskAssessment assessment = MyBatisSqlRiskClassifier.assess(sql);
        assertEquals(expected, assessment.risk());
        assertEquals(confirmation, assessment.confirmationRequired());
        assertTrue(assessment.structurallyValid());
    }
}
