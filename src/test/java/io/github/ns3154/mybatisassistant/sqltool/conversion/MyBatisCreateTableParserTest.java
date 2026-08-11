package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;

import java.sql.Types;

public final class MyBatisCreateTableParserTest extends BasePlatformTestCase {
    public void testParsesQualifiedMySqlTableColumnsKeysAndComments() {
        MyBatisCreateTableParseResult.Success success = success("""
                CREATE TABLE IF NOT EXISTS `app`.`user_account` (
                  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
                  `role_id` BIGINT NOT NULL,
                  `display_name` VARCHAR(120) NULL COMMENT '显示名',
                  `created_at` DATETIME NOT NULL,
                  CONSTRAINT `pk_user` PRIMARY KEY (`id`),
                  CONSTRAINT `fk_role` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`),
                  UNIQUE KEY `uk_name` (`display_name`)
                ) ENGINE=InnoDB COMMENT='用户账户';
                """);

        assertEquals("app", success.table().schema().orElseThrow());
        assertEquals("user_account", success.table().name());
        assertEquals("用户账户", success.table().comment().orElseThrow());
        assertEquals(4, success.table().columns().size());
        MyBatisDatabaseColumn id = success.table().columns().get(0);
        assertEquals(Types.BIGINT, id.jdbcType());
        assertTrue(id.primaryKey());
        assertTrue(id.autoIncrement());
        assertFalse(id.nullable());
        assertEquals("主键", id.comment().orElseThrow());
        assertTrue(success.table().columns().get(1).foreignKey());
        assertEquals("显示名", success.table().columns().get(2).comment().orElseThrow());
        assertEmpty(success.warnings());
        assertFalse(success.confirmationRequired());
    }

    public void testMapsCommonPostgresTypesAndWarnsForUnknownType() {
        MyBatisCreateTableParseResult.Success success = success("""
                CREATE TABLE audit_event (
                  id BIGSERIAL PRIMARY KEY,
                  amount NUMERIC(19, 2) NOT NULL,
                  happened_at TIMESTAMP WITH TIME ZONE,
                  payload JSONB
                );
                """);

        assertEquals(Types.BIGINT, success.table().columns().get(0).jdbcType());
        assertTrue(success.table().columns().get(0).autoIncrement());
        assertEquals(Types.DECIMAL, success.table().columns().get(1).jdbcType());
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE,
                success.table().columns().get(2).jdbcType());
        assertEquals(Types.OTHER, success.table().columns().get(3).jdbcType());
        assertEquals(1, success.warnings().size());
        assertTrue(success.warnings().getFirst().contains("payload"));
        assertTrue(success.confirmationRequired());
    }

    public void testSupportsQuotedColumnAndEscapedComment() {
        MyBatisCreateTableParseResult.Success success = success("""
                CREATE TABLE "order" (
                  "select" CHARACTER VARYING(50) COMMENT 'customer''s value',
                  [binary_data] VARBINARY(64)
                )
                """);

        assertEquals("order", success.table().name());
        assertEquals("select", success.table().columns().get(0).name());
        assertEquals("customer's value",
                success.table().columns().get(0).comment().orElseThrow());
        assertEquals(Types.VARBINARY, success.table().columns().get(1).jdbcType());
    }

    public void testIgnoresSqlCommentsWithoutRemovingCommentLiterals() {
        MyBatisCreateTableParseResult.Success success = success("""
                -- schema fixture
                CREATE TABLE sample (
                  id BIGINT, /* nested /* detail */ note */
                  text_value VARCHAR(50) COMMENT 'keep -- and /* literal */'
                ); # trailing comment
                """);

        assertEquals(2, success.table().columns().size());
        assertEquals("keep -- and /* literal */",
                success.table().columns().get(1).comment().orElseThrow());
        assertFailure("CREATE TABLE sample (id BIGINT /* missing close)",
                MyBatisDdlDiagnosticCode.MALFORMED_DDL);
    }

    public void testRejectsNonCreateMultipleMalformedDuplicateAndEmpty() {
        assertFailure("SELECT 1", MyBatisDdlDiagnosticCode.NOT_CREATE_TABLE);
        assertFailure("CREATE TABLE a (id INT); DROP TABLE b;",
                MyBatisDdlDiagnosticCode.MULTIPLE_STATEMENTS);
        assertFailure("CREATE TABLE a (id INT",
                MyBatisDdlDiagnosticCode.MALFORMED_DDL);
        assertFailure("CREATE TABLE a (id INT, id BIGINT)",
                MyBatisDdlDiagnosticCode.DUPLICATE_COLUMN);
        assertFailure("CREATE TABLE a ()", MyBatisDdlDiagnosticCode.NO_COLUMNS);
        assertFailure("CREATE TABLE a (CONSTRAINT only_check CHECK (1=1))",
                MyBatisDdlDiagnosticCode.NO_COLUMNS);
    }

    public void testRejectsUnsupportedColumnAndOversizedInput() {
        assertFailure("CREATE TABLE a (123 invalid)",
                MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);
        assertFailure("中".repeat(MyBatisCreateTableParser.MAX_INPUT_BYTES / 2),
                MyBatisDdlDiagnosticCode.INPUT_TOO_LARGE);
    }

    private static MyBatisCreateTableParseResult.Success success(String ddl) {
        MyBatisCreateTableParseResult result = MyBatisCreateTableParser.parse(ddl);
        assertInstanceOf(result, MyBatisCreateTableParseResult.Success.class);
        return (MyBatisCreateTableParseResult.Success) result;
    }

    private static void assertFailure(String ddl, MyBatisDdlDiagnosticCode code) {
        MyBatisCreateTableParseResult result = MyBatisCreateTableParser.parse(ddl);
        assertInstanceOf(result, MyBatisCreateTableParseResult.Failure.class);
        MyBatisCreateTableParseResult.Failure failure =
                (MyBatisCreateTableParseResult.Failure) result;
        assertEquals(code, failure.code());
        assertFalse(failure.message().isBlank());
    }
}
