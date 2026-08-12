package io.github.ns3154.mybatisassistant.sqltool.execution;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRisk;

public final class MyBatisSqlExecutionPolicyTest extends BasePlatformTestCase {
    public void testReadOnlySingleStatementIsReadyWithoutDoubleConfirmation() {
        MyBatisSqlExecutionPlan plan = ready(
                "SELECT * FROM users WHERE id = ? AND note = '?' /* ? */",
                "LONG:7");

        assertEquals(MyBatisSqlRisk.READ_ONLY, plan.riskAssessment().risk());
        assertFalse(plan.doubleConfirmationRequired());
        assertInstanceOf(MyBatisSqlExecutionPolicy.authorize(plan, false, ""),
                MyBatisSqlExecutionAuthorization.Authorized.class);
    }

    public void testWriteDdlAndUnknownRequireRiskAndTypedConfirmation() {
        for (String sql : new String[]{
                "UPDATE users SET active = FALSE WHERE id = 1",
                "DROP TABLE audit_log",
                "CALL rebuild_index()"}) {
            MyBatisSqlExecutionPlan plan = ready(sql, "");
            assertTrue(plan.doubleConfirmationRequired());
            assertInstanceOf(MyBatisSqlExecutionPolicy.authorize(plan, false, ""),
                    MyBatisSqlExecutionAuthorization.Rejected.class);
            assertInstanceOf(MyBatisSqlExecutionPolicy.authorize(plan, true, "执行"),
                    MyBatisSqlExecutionAuthorization.Rejected.class);
            assertInstanceOf(MyBatisSqlExecutionPolicy.authorize(
                            plan, true, MyBatisSqlExecutionPolicy.dangerousConfirmationPhrase()),
                    MyBatisSqlExecutionAuthorization.Authorized.class);
        }
    }

    public void testRejectsEmptyOversizeDynamicMalformedAndMultipleSql() {
        assertRejected("", "");
        assertRejected("x".repeat(MyBatisSqlExecutionPolicy.MAX_SQL_BYTES + 1), "");
        assertRejected("SELECT * FROM users WHERE id = #{id}", "");
        assertRejected("SELECT 'unterminated", "");
        assertRejected("SELECT 1; SELECT 2", "");
        assertRejected("-- comment only", "");
        assertRejected("SELECT ?", "STRING:" + "x".repeat(
                MyBatisSqlExecutionPolicy.MAX_PARAMETER_PANEL_BYTES + 1));
    }

    public void testRejectsParameterSyntaxAndPlaceholderCountMismatch() {
        assertRejected("SELECT ?", "LONG:invalid");
        assertRejected("SELECT ?, ?", "LONG:1");
        assertRejected("SELECT 1", "STRING:extra");
    }

    public void testPlanAndAuthorizationToStringRedactSqlAndParameters() {
        MyBatisSqlExecutionPlan plan = ready(
                "SELECT * FROM secret_table WHERE token = ?",
                "STRING:secret-token");
        MyBatisSqlExecutionAuthorization.Authorized authorization =
                (MyBatisSqlExecutionAuthorization.Authorized)
                        MyBatisSqlExecutionPolicy.authorize(plan, false, "");

        assertFalse(plan.toString().contains("secret_table"));
        assertFalse(plan.toString().contains("secret-token"));
        assertFalse(authorization.execution().toString().contains("secret_table"));
    }

    private static MyBatisSqlExecutionPlan ready(String sql, String parameters) {
        MyBatisSqlExecutionPreparation preparation =
                MyBatisSqlExecutionPolicy.prepare(sql, parameters);
        assertInstanceOf(preparation, MyBatisSqlExecutionPreparation.Ready.class);
        return ((MyBatisSqlExecutionPreparation.Ready) preparation).plan();
    }

    private static void assertRejected(String sql, String parameters) {
        MyBatisSqlExecutionPreparation preparation =
                MyBatisSqlExecutionPolicy.prepare(sql, parameters);
        assertInstanceOf(preparation, MyBatisSqlExecutionPreparation.Rejected.class);
        assertFalse(((MyBatisSqlExecutionPreparation.Rejected) preparation)
                .message().isBlank());
    }
}
