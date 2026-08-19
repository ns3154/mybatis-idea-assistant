package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodNameParser;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodOperation;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodParseResult;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSchema;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSqlGenerator;

import java.sql.Types;
import java.util.Set;

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

    public void testPreservesQuotedDotsAndThreePartTableIdentity() {
        for (String quoted : java.util.List.of("\"a.b\"", "`a.b`", "[a.b]")) {
            MyBatisCreateTableParseResult.Success success = success(
                    "CREATE TABLE " + quoted + " (id BIGINT)");
            assertTrue(success.table().catalog().isEmpty());
            assertTrue(success.table().schema().isEmpty());
            assertEquals("a.b", success.table().name());
        }

        MyBatisCreateTableParseResult.Success qualified = success(
                "CREATE TABLE app.\"a.b\" (id BIGINT)");
        assertTrue(qualified.table().catalog().isEmpty());
        assertEquals("app", qualified.table().schema().orElseThrow());
        assertEquals("a.b", qualified.table().name());

        MyBatisCreateTableParseResult.Success threePart = success(
                "CREATE TABLE [tenant.catalog].[app.schema].[user.table] (id BIGINT)");
        assertEquals("tenant.catalog", threePart.table().catalog().orElseThrow());
        assertEquals("app.schema", threePart.table().schema().orElseThrow());
        assertEquals("user.table", threePart.table().name());

        MyBatisMethodSchema schema = MyBatisMethodSchema.from(
                qualified.table(), MyBatisGenerationConfiguration.standard("com.example"));
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", schema);
        MyBatisMethodGeneration generation = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        schema,
                        parsed.query(),
                        MyBatisSqlDialect.POSTGRESQL,
                        "com.example.AB",
                        true,
                        Set.of()));
        assertTrue(generation.xmlStatement().contains("FROM \"app\".\"a.b\""));
    }

    public void testPreservesQuotedCommasParenthesesAndEscapedConstraintColumns() {
        MyBatisCreateTableParseResult.Success doubleQuoted = success("""
                CREATE TABLE quoted_keys (
                  "a,b" BIGINT,
                  "r)b" BIGINT,
                  PRIMARY KEY ("a,b"),
                  FOREIGN KEY ("r)b") REFERENCES other_table (id)
                )
                """);
        assertTrue(doubleQuoted.table().columns().get(0).primaryKey());
        assertTrue(doubleQuoted.table().columns().get(1).foreignKey());

        MyBatisCreateTableParseResult.Success backtick = success("""
                CREATE TABLE escaped_backtick (
                  `a``b,c` BIGINT,
                  PRIMARY KEY (`a``b,c`)
                )
                """);
        assertEquals("a`b,c", backtick.table().columns().getFirst().name());
        assertTrue(backtick.table().columns().getFirst().primaryKey());

        MyBatisCreateTableParseResult.Success bracket = success("""
                CREATE TABLE escaped_bracket (
                  [a]]b,)] BIGINT,
                  PRIMARY KEY ([a]]b,)])
                )
                """);
        assertEquals("a]b,)", bracket.table().columns().getFirst().name());
        assertTrue(bracket.table().columns().getFirst().primaryKey());

        String xml = MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                        "main",
                        MyBatisSqlDialect.H2,
                        doubleQuoted.table(),
                        MyBatisGenerationConfiguration.standard("com.example")))
                .artifacts().stream()
                .filter(artifact -> artifact.kind() == MyBatisGenerationArtifactKind.XML)
                .findFirst()
                .orElseThrow()
                .content();
        assertTrue(xml.contains("selectByPrimaryKey"));
        assertTrue(xml.contains("updateByPrimaryKey"));

        MyBatisCreateTableParseResult.Success checkLiteral = success("""
                CREATE TABLE check_literal (
                  id BIGINT,
                  note VARCHAR(50),
                  CHECK (note <> 'PRIMARY KEY (id)')
                )
                """);
        assertFalse(checkLiteral.table().columns().getFirst().primaryKey());

        MyBatisCreateTableParseResult.Success modifierLiterals = success("""
                CREATE TABLE modifier_literals (
                  id INTEGER COMMENT 'PRIMARY KEY GENERATED ALWAYS AS IDENTITY',
                  parent_id INTEGER DEFAULT 'NOT NULL REFERENCES parent(id)',
                  note VARCHAR(50) COMMENT 'AUTO_INCREMENT GENERATED'
                )
                """);
        for (MyBatisDatabaseColumn column : modifierLiterals.table().columns()) {
            assertFalse(column.primaryKey());
            assertFalse(column.foreignKey());
            assertFalse(column.autoIncrement());
            assertFalse(column.generated());
            assertTrue(column.nullable());
        }

        assertFailure(
                "CREATE TABLE malformed (id BIGINT, PRIMARY KEY (id extra))",
                MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);
    }

    public void testParsesQuotedConstraintNamesWithoutCreatingFakeColumns() {
        for (String[] names : java.util.List.of(
                new String[]{"normal_primary", "normal_foreign"},
                new String[]{"\"order primary\"", "\"order foreign\""},
                new String[]{"`order primary`", "`order foreign`"},
                new String[]{"[order primary]", "[order foreign]"})) {
            MyBatisCreateTableParseResult.Success success = success("""
                    CREATE TABLE named_constraint (
                      id BIGINT,
                      parent_id BIGINT,
                      CONSTRAINT %s PRIMARY KEY (id),
                      CONSTRAINT %s FOREIGN KEY (parent_id) REFERENCES parent_table (id)
                    )
                    """.formatted(names[0], names[1]));

            assertEquals(2, success.table().columns().size());
            assertEquals("id", success.table().columns().get(0).name());
            assertTrue(success.table().columns().get(0).primaryKey());
            assertEquals("parent_id", success.table().columns().get(1).name());
            assertTrue(success.table().columns().get(1).foreignKey());
        }

        assertFailure(
                "CREATE TABLE malformed (id BIGINT, CONSTRAINT \"missing kind\")",
                MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);
    }

    public void testRejectsUnsupportedDollarQuotedDefaultsBeforeModifierInference() {
        assertFailure("""
                CREATE TABLE dollar_default (
                  id TEXT DEFAULT $$PRIMARY KEY GENERATED AS IDENTITY$$,
                  note TEXT DEFAULT $tag$NOT NULL REFERENCES parent(id)$tag$
                )
                """, MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);

        MyBatisCreateTableParseResult.Success quotedIdentifier = success("""
                CREATE TABLE "$tag$" (
                  "$column$" TEXT DEFAULT 'PRIMARY KEY'
                )
                """);
        assertEquals("$tag$", quotedIdentifier.table().name());
        assertFalse(quotedIdentifier.table().columns().getFirst().primaryKey());
    }

    public void testMarksComputedColumnsGeneratedAndKeepsIdentityAsAutoIncrement() {
        MyBatisCreateTableParseResult.Success success = success("""
                CREATE TABLE metric (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  raw_value INTEGER NOT NULL,
                  doubled INTEGER GENERATED ALWAYS AS (raw_value * 2) STORED,
                  tripled INTEGER AS (raw_value * 3) VIRTUAL
                )
                """);

        MyBatisDatabaseColumn id = success.table().columns().get(0);
        MyBatisDatabaseColumn doubled = success.table().columns().get(2);
        MyBatisDatabaseColumn tripled = success.table().columns().get(3);
        assertTrue(id.autoIncrement());
        assertFalse(id.generated());
        assertFalse(doubled.autoIncrement());
        assertTrue(doubled.generated());
        assertTrue(tripled.generated());
        assertFailure(
                "CREATE TABLE metric (raw_value INTEGER, doubled AS (raw_value * 2))",
                MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);
        assertFailure(
                "CREATE TABLE metric (raw_value INTEGER, doubled AS raw_value * 2)",
                MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION);

        MyBatisCreateTableParseResult.Success identityWord = success("""
                CREATE TABLE identity_word (
                  identity INTEGER NOT NULL,
                  copied INTEGER GENERATED ALWAYS AS (identity + 1) STORED
                )
                """);
        assertTrue(identityWord.table().columns().get(1).generated());
        assertFalse(identityWord.table().columns().get(1).autoIncrement());

        MyBatisCreateTableParseResult.Success sqlServerIdentity = success("""
                CREATE TABLE sql_server_identity (
                  id BIGINT IDENTITY(1, 1) NOT FOR REPLICATION PRIMARY KEY,
                  name NVARCHAR(20) NOT NULL
                )
                """);
        assertTrue(sqlServerIdentity.table().columns().getFirst().autoIncrement());
        String sqlServerXml = MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                        "main",
                        MyBatisSqlDialect.SQL_SERVER,
                        sqlServerIdentity.table(),
                        MyBatisGenerationConfiguration.standard("com.example")))
                .artifacts().stream()
                .filter(artifact -> artifact.kind() == MyBatisGenerationArtifactKind.XML)
                .findFirst()
                .orElseThrow()
                .content();
        assertTrue(sqlServerXml.contains(
                "useGeneratedKeys=\"true\" keyProperty=\"id\""));
        assertTrue(sqlServerXml.contains("([name]) VALUES (#{name,jdbcType=NVARCHAR})"));
        String insertStatement = sqlServerXml.substring(
                sqlServerXml.indexOf("<insert id=\"insert\""),
                sqlServerXml.indexOf("</insert>") + "</insert>".length());
        assertFalse(insertStatement.contains("#{id"));
    }

    public void testExcludesParsedComputedColumnsFromBatchInsert() {
        MyBatisCreateTableParseResult.Success success = success("""
                CREATE TABLE metric (
                  id BIGINT PRIMARY KEY,
                  raw_value INTEGER NOT NULL,
                  doubled INTEGER GENERATED ALWAYS AS (raw_value * 2) STORED
                )
                """);
        MyBatisMethodSchema schema = MyBatisMethodSchema.from(
                success.table(),
                MyBatisGenerationConfiguration.standard("com.example"));
        MyBatisMethodParseResult result = MyBatisMethodNameParser.parse(
                "insertBatch", schema);
        assertInstanceOf(result, MyBatisMethodParseResult.Success.class);
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success) result;

        assertEquals(MyBatisMethodOperation.INSERT_BATCH, parsed.query().operation());
        assertEquals(java.util.List.of("id", "rawValue"),
                parsed.query().subjectFields().stream()
                        .map(field -> field.propertyName())
                        .toList());
        MyBatisMethodGeneration generation = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        schema,
                        parsed.query(),
                        MyBatisSqlDialect.H2,
                        "com.example.Metric",
                        true,
                        Set.of()));
        assertTrue(generation.xmlStatement().contains("(\"id\", \"raw_value\") VALUES"));
        assertFalse(generation.xmlStatement().contains("entity.doubled"));
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
