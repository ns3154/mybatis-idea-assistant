package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.junit.Test;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MyBatisMethodSqlGeneratorTest {
    private static final MyBatisMethodSchema SCHEMA = new MyBatisMethodSchema(
            "user_account",
            List.of(
                    field("id", "Id", "id", "java.lang.Long", Types.BIGINT),
                    field("name", "Name", "name", "java.lang.String", Types.VARCHAR),
                    field("email", "Email", "email", "java.lang.String", Types.VARCHAR),
                    field("status", "Status", "status", "java.lang.String", Types.VARCHAR),
                    field("age", "Age", "age", "java.lang.Integer", Types.INTEGER),
                    field("active", "Active", "active", "java.lang.Boolean", Types.BOOLEAN),
                    field("balance", "Balance", "balance", "java.math.BigDecimal", Types.DECIMAL),
                    field("createdAt", "CreatedAt", "created_at",
                            "java.time.LocalDateTime", Types.TIMESTAMP)));

    @Test
    public void generatesEntityListMethodAndStaticXml() throws Exception {
        MyBatisMethodGeneration generation = generate(
                "findByStatusAndAgeGreaterThanOrderByCreatedAtDesc",
                MyBatisSqlDialect.POSTGRESQL,
                Set.of());

        assertEquals("java.util.List<com.example.UserAccount>", generation.returnType());
        assertEquals(List.of("status", "age"), names(generation));
        assertEquals("java.util.List<com.example.UserAccount> "
                        + "findByStatusAndAgeGreaterThanOrderByCreatedAtDesc("
                        + "@org.apache.ibatis.annotations.Param(\"status\") "
                        + "java.lang.String status, "
                        + "@org.apache.ibatis.annotations.Param(\"age\") "
                        + "java.lang.Integer age);",
                generation.javaMethod());
        assertTrue(generation.xmlStatement().contains(
                "WHERE \"status\" = #{status,jdbcType=VARCHAR} "
                        + "AND \"age\" > #{age,jdbcType=INTEGER}"));
        assertTrue(generation.xmlStatement().contains("ORDER BY \"created_at\" DESC"));
        assertTrue(generation.xmlStatement().contains(
                "<select id=\"findByStatusAndAgeGreaterThanOrderByCreatedAtDesc\" "
                        + "resultMap=\"BaseResultMap\">"));
        assertFalse(generation.xmlStatement().contains("resultType="));
        assertFalse(generation.dynamic());
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void derivesSingleAndProjectedReturnTypes() {
        MyBatisMethodGeneration entity = generate(
                "getById", MyBatisSqlDialect.GENERIC, Set.of());
        MyBatisMethodGeneration scalar = generate(
                "findEmailByStatus", MyBatisSqlDialect.GENERIC, Set.of());
        MyBatisMethodGeneration map = generate(
                "findNameAndEmailByStatus", MyBatisSqlDialect.GENERIC, Set.of());

        assertEquals("java.util.Optional<com.example.UserAccount>", entity.returnType());
        assertTrue(entity.xmlStatement().contains("resultMap=\"BaseResultMap\""));
        assertFalse(entity.xmlStatement().contains("resultType="));
        assertEquals("java.util.List<java.lang.String>", scalar.returnType());
        assertTrue(scalar.xmlStatement().contains("resultType=\"java.lang.String\""));
        assertFalse(scalar.xmlStatement().contains("resultMap="));
        assertEquals(
                "java.util.List<java.util.Map<java.lang.String, java.lang.Object>>",
                map.returnType());
        assertTrue(map.xmlStatement().contains("resultType=\"map\""));
        assertFalse(map.xmlStatement().contains("resultMap="));
    }

    @Test
    public void rejectsReadonlyUpdateSubjectsBeforeGeneratingXml() {
        MyBatisMethodField autoIncrementId = new MyBatisMethodField(
                "id", "Id", "id", "java.lang.Long", Optional.empty(),
                Types.BIGINT, false, true, false, true, false);
        MyBatisMethodField generatedDigest = new MyBatisMethodField(
                "digest", "Digest", "digest", "java.lang.String", Optional.empty(),
                Types.VARCHAR, false, false, false, false, true);
        MyBatisMethodField name = field(
                "name", "Name", "name", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "users", List.of(autoIncrementId, generatedDigest, name));

        for (MyBatisMethodField readonly : List.of(autoIncrementId, generatedDigest)) {
            MyBatisMethodQuery forged = new MyBatisMethodQuery(
                    "update" + readonly.methodToken() + "ByName",
                    MyBatisMethodOperation.UPDATE,
                    List.of(readonly),
                    false,
                    OptionalInt.empty(),
                    false,
                    false,
                    Optional.of(new MyBatisMethodCondition(
                            name, MyBatisMethodComparison.EQUALS)),
                    List.of());

            assertTrue(assertThrows(
                    IllegalArgumentException.class,
                    () -> MyBatisMethodSqlGenerator.generate(
                            new MyBatisMethodGenerationRequest(
                                    schema,
                                    forged,
                                    MyBatisSqlDialect.H2,
                                    "com.example.UserAccount",
                                    true,
                                    Set.of())))
                    .getMessage().contains("唯一解析"));
        }
    }

    @Test
    public void rejectsDuplicateUpdateSubjectsBeforeGeneratingXml() {
        MyBatisMethodField email = SCHEMA.fields().stream()
                .filter(field -> "email".equals(field.propertyName()))
                .findFirst()
                .orElseThrow();
        MyBatisMethodField id = SCHEMA.fields().getFirst();
        MyBatisMethodQuery forged = new MyBatisMethodQuery(
                "updateEmailAndEmailById",
                MyBatisMethodOperation.UPDATE,
                List.of(email, email),
                false,
                OptionalInt.empty(),
                false,
                false,
                Optional.of(new MyBatisMethodCondition(
                        id, MyBatisMethodComparison.EQUALS)),
                List.of());

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                SCHEMA,
                                forged,
                                MyBatisSqlDialect.H2,
                                "com.example.UserAccount",
                                true,
                                Set.of())))
                .getMessage().contains("唯一解析"));
    }

    @Test
    public void generatesUpdateWithIndependentValueAndConditionParameters() throws Exception {
        MyBatisMethodGeneration generation = generate(
                "updateStatusAndNameByIdAndStatus",
                MyBatisSqlDialect.MYSQL,
                Set.of());

        assertEquals("int", generation.returnType());
        assertEquals(List.of("newStatus", "newName", "id", "status"), names(generation));
        assertTrue(generation.xmlStatement().contains(
                "UPDATE `user_account` SET `status` = #{newStatus,jdbcType=VARCHAR}, "
                        + "`name` = #{newName,jdbcType=VARCHAR}"));
        assertTrue(generation.xmlStatement().contains(
                "WHERE `id` = #{id,jdbcType=BIGINT} AND "
                        + "`status` = #{status,jdbcType=VARCHAR}"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void generatesSetBasedCollectionUpdateAndDelete() throws Exception {
        MyBatisMethodGeneration update = generate(
                "updateStatusByIdIn",
                MyBatisSqlDialect.MYSQL,
                Set.of());
        MyBatisMethodGeneration delete = generate(
                "deleteByIdNotIn",
                MyBatisSqlDialect.MYSQL,
                Set.of());

        assertEquals(List.of("newStatus", "idValues"), names(update));
        assertEquals("java.util.Collection<java.lang.Long>",
                update.parameters().get(1).javaType());
        assertTrue(update.xmlStatement().contains(
                "UPDATE `user_account` SET `status` = #{newStatus,jdbcType=VARCHAR}"));
        assertTrue(update.xmlStatement().contains(
                "WHERE <choose><when test=\"idValues != null and !idValues.isEmpty() "
                        + "and @java.util.Collections@frequency(idValues, null) == 0\">"
                        + "`id` IN <foreach collection=\"idValues\" item=\"item\""));
        assertEquals(List.of("idValues"), names(delete));
        assertTrue(delete.xmlStatement().contains("DELETE FROM `user_account`"));
        assertTrue(delete.xmlStatement().contains(
                "WHERE <choose><when test=\"idValues != null and !idValues.isEmpty() "
                        + "and @java.util.Collections@frequency(idValues, null) == 0\">"
                        + "`id` NOT IN <foreach collection=\"idValues\" item=\"item\""));
        assertFalse(update.xmlStatement().contains("${"));
        assertFalse(delete.xmlStatement().contains("${"));
        assertFalse(update.sqlPreview().contains(";"));
        assertFalse(delete.sqlPreview().contains(";"));
        assertWellFormed(update.xmlStatement());
        assertWellFormed(delete.xmlStatement());
    }

    @Test
    public void generatesExplicitMultiRowBatchInsertFromAstRoles() throws Exception {
        MyBatisMethodField generatedId = new MyBatisMethodField(
                "id", "Id", "id", "java.lang.Long", Optional.empty(), Types.BIGINT,
                false, true, false, true);
        MyBatisMethodField name = field(
                "name", "Name", "name", "java.lang.String", Types.VARCHAR);
        MyBatisMethodField email = field(
                "email", "Email", "email", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "user_account", List.of(generatedId, name, email));

        MyBatisMethodGeneration generation = generate(
                "insertBatch", schema, MyBatisSqlDialect.H2, Set.of());

        assertEquals("int", generation.returnType());
        assertEquals(List.of("entities"), names(generation));
        assertEquals(MyBatisMethodParameterRole.BATCH_ENTITIES,
                generation.parameters().getFirst().role());
        assertEquals("java.util.Collection<com.example.UserAccount>",
                generation.parameters().getFirst().javaType());
        assertTrue(generation.javaMethod().contains(
                "insertBatch(@org.apache.ibatis.annotations.Param(\"entities\") "));
        assertTrue(generation.xmlStatement().contains("<insert id=\"insertBatch\">"));
        assertTrue(generation.xmlStatement().contains(
                "<bind name=\"_mybatisAssistantBatchEntities\" "
                        + "value=\"@java.util.Collections@list("
                        + "@java.util.Collections@enumeration("
                        + "@java.util.Objects@requireNonNull(entities)))\"/>"));
        assertTrue(generation.xmlStatement().contains(
                "_mybatisAssistantBatchEntities.iterator().next()"));
        assertTrue(generation.xmlStatement().contains(
                "@java.util.Objects@checkIndex("
                        + "@java.util.Collections@frequency("
                        + "_mybatisAssistantBatchEntities, null), 1)"));
        assertTrue(generation.xmlStatement().contains(
                "INSERT INTO \"user_account\" (\"name\", \"email\") VALUES"));
        assertTrue(generation.xmlStatement().contains(
                "<foreach collection=\"_mybatisAssistantBatchEntities\" "
                        + "item=\"entity\" separator=\",\">"));
        assertTrue(generation.xmlStatement().contains(
                "#{entity.name,jdbcType=VARCHAR}, #{entity.email,jdbcType=VARCHAR}"));
        assertFalse(generation.xmlStatement().contains("#{entity.id"));
        assertFalse(generation.xmlStatement().contains("WHERE 1 = 0"));
        assertFalse(generation.xmlStatement().contains("${"));
        assertTrue(generation.dynamic());
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void excludesAutoIncrementAndGeneratedColumnsFromBatchInsert()
            throws Exception {
        MyBatisMethodField generatedId = new MyBatisMethodField(
                "id", "Id", "id", "java.lang.Long", Optional.empty(), Types.BIGINT,
                false, true, false, true, false);
        MyBatisMethodField generatedDigest = new MyBatisMethodField(
                "digest", "Digest", "digest", "java.lang.String", Optional.empty(),
                Types.VARCHAR, true, false, false, false, true);
        MyBatisMethodField name = field(
                "name", "Name", "name", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "user_account", List.of(generatedId, generatedDigest, name));

        MyBatisMethodGeneration generation = generate(
                "insertBatch", schema, MyBatisSqlDialect.H2, Set.of());

        assertTrue(generation.xmlStatement().contains("(\"name\") VALUES"));
        assertFalse(generation.xmlStatement().contains("entity.id"));
        assertFalse(generation.xmlStatement().contains("entity.digest"));
    }

    @Test
    public void generatesOracleInsertAllAndPreservesTypeHandler() throws Exception {
        MyBatisMethodField coded = new MyBatisMethodField(
                "code", "Code", "code", "com.example.Code",
                Optional.of("com.example.CodeTypeHandler"),
                Types.VARCHAR, false, false, false);
        MyBatisMethodSchema schema = new MyBatisMethodSchema("sample", List.of(coded));

        MyBatisMethodGeneration generation = generate(
                "insertBatch", schema, MyBatisSqlDialect.ORACLE, Set.of());

        assertTrue(generation.xmlStatement().contains("INSERT ALL"));
        assertTrue(generation.xmlStatement().contains("SELECT 1 FROM DUAL"));
        assertTrue(generation.xmlStatement().contains(
                "#{entity.code,jdbcType=VARCHAR,"
                        + "typeHandler=com.example.CodeTypeHandler}"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void usesTheSelectedDialectBatchInsertForm() throws Exception {
        for (MyBatisSqlDialect dialect : MyBatisSqlDialect.values()) {
            MyBatisMethodGeneration generation = generate(
                    "insertBatch", dialect, Set.of());
            if (dialect == MyBatisSqlDialect.ORACLE) {
                assertTrue(generation.xmlStatement(),
                        generation.xmlStatement().contains("INSERT ALL"));
                assertTrue(generation.xmlStatement(),
                        generation.xmlStatement().contains("SELECT 1 FROM DUAL"));
            } else {
                assertTrue(generation.xmlStatement(),
                        generation.xmlStatement().contains("INSERT INTO"));
                assertTrue(generation.xmlStatement(),
                        generation.xmlStatement().contains("separator=\",\""));
                assertFalse(generation.xmlStatement(),
                        generation.xmlStatement().contains("INSERT ALL"));
            }
            assertTrue(generation.xmlStatement(), generation.xmlStatement().contains(
                    "@java.util.Collections@enumeration("
                            + "@java.util.Objects@requireNonNull(entities))"));
            if (dialect == MyBatisSqlDialect.SQL_SERVER) {
                long insertFieldCount = SCHEMA.fields().stream()
                        .filter(field -> !field.autoIncrement() && !field.generated())
                        .count();
                long maximumRows = Math.min(1000, 2100 / insertFieldCount);
                assertTrue(generation.xmlStatement(), generation.xmlStatement().contains(
                        "@java.util.Objects@checkIndex("
                                + "_mybatisAssistantBatchEntities.size() - 1, "
                                + maximumRows + ")"));
            }
            assertWellFormed(generation.xmlStatement());
        }
    }

    @Test
    public void rejectsForgedOrStaleBatchInsertAst() {
        MyBatisMethodQuery forged = new MyBatisMethodQuery(
                "insertBatch",
                MyBatisMethodOperation.INSERT_BATCH,
                List.of(SCHEMA.fields().getFirst()),
                false,
                OptionalInt.empty(),
                false,
                false,
                Optional.empty(),
                List.of());

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                SCHEMA,
                                forged,
                                MyBatisSqlDialect.H2,
                                "com.example.UserAccount",
                                true,
                                Set.of())))
                .getMessage().contains("AST"));
    }

    @Test
    public void rejectsForgedWriteAstWithoutCanonicalPredicate() {
        MyBatisMethodQuery parsed = ((MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("deleteById", SCHEMA)).query();
        MyBatisMethodQuery forged = new MyBatisMethodQuery(
                parsed.methodName(),
                parsed.operation(),
                parsed.subjectFields(),
                parsed.distinct(),
                parsed.limit(),
                parsed.paged(),
                parsed.singleResult(),
                Optional.empty(),
                parsed.orders());

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                SCHEMA,
                                forged,
                                MyBatisSqlDialect.H2,
                                "com.example.UserAccount",
                                true,
                                Set.of())))
                .getMessage().contains("唯一解析"));
    }

    @Test
    public void failsClosedForNullEmptyOrNullContainingMandatoryCollections()
            throws Exception {
        MyBatisMethodGeneration update = generate(
                "updateStatusByIdIn", MyBatisSqlDialect.MYSQL, Set.of());
        MyBatisMethodGeneration delete = generate(
                "deleteByIdNotIn", MyBatisSqlDialect.MYSQL, Set.of());
        MyBatisMethodGeneration select = generate(
                "findByIdIn", MyBatisSqlDialect.MYSQL, Set.of());

        for (MyBatisMethodGeneration generation : List.of(update, delete, select)) {
            assertTrue(generation.xmlStatement().contains(
                    "<when test=\"idValues != null and !idValues.isEmpty() "
                            + "and @java.util.Collections@frequency(idValues, null) == 0\">"));
            assertTrue(generation.xmlStatement().contains(
                    "<otherwise>1 = 0</otherwise>"));
            assertWellFormed(generation.xmlStatement());
        }
    }

    @Test
    public void guardsTheWholeOrPredicateWhenAMandatoryCollectionIsEmpty()
            throws Exception {
        MyBatisMethodGeneration select = generate(
                "findByIdInOrStatus",
                MyBatisSqlDialect.H2,
                Set.of());
        MyBatisMethodGeneration update = generate(
                "updateEmailByIdInOrStatus",
                MyBatisSqlDialect.H2,
                Set.of());

        for (MyBatisMethodGeneration generation : List.of(select, update)) {
            assertTrue(generation.xmlStatement().contains(
                    "WHERE <choose><when test=\"idValues != null "
                            + "and !idValues.isEmpty() "
                            + "and @java.util.Collections@frequency(idValues, null) == 0\">"));
            assertTrue(generation.xmlStatement().contains(
                    "\"id\" IN <foreach"));
            assertTrue(generation.xmlStatement().contains(
                    " OR \"status\" = #{status,jdbcType=VARCHAR}"));
            assertTrue(generation.xmlStatement().contains(
                    "</when><otherwise>1 = 0</otherwise></choose>"));
            assertWellFormed(generation.xmlStatement());
        }
    }

    @Test
    public void rejectsWritesWhoseEveryPredicateIsOptional() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "updateStatusByIdAndName",
                        MyBatisSqlDialect.GENERIC,
                        Set.of(0, 1)))
                .getMessage().contains("全部谓词"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "deleteByIdIn",
                        MyBatisSqlDialect.GENERIC,
                        Set.of(0)))
                .getMessage().contains("全部谓词"));
    }

    @Test
    public void safelyOmitsAnOptionalEmptyCollectionWhenAnotherWriteGuardRemains()
            throws Exception {
        MyBatisMethodGeneration generation = generate(
                "updateStatusByIdInAndName",
                MyBatisSqlDialect.GENERIC,
                Set.of(0));

        assertTrue(generation.xmlStatement().contains(
                "<when test=\"idValues != null and !idValues.isEmpty() "
                        + "and @java.util.Collections@frequency(idValues, null) == 0\">"
                        + "AND \"id\" IN <foreach"));
        assertTrue(generation.xmlStatement().contains(
                "<when test=\"idValues != null and !idValues.isEmpty() "
                        + "and @java.util.Collections@frequency(idValues, null) != 0\">"
                        + "AND 1 = 0</when>"));
        assertTrue(generation.xmlStatement().contains(
                "AND \"name\" = #{name,jdbcType=VARCHAR}"));
        assertFalse(generation.xmlStatement().contains(
                "<if test=\"idValues != null\">"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void allocatesStableNamesForRepeatedFieldsAndRanges() {
        MyBatisMethodGeneration repeated = generate(
                "findByAgeGreaterThanAndAgeLessThan",
                MyBatisSqlDialect.GENERIC,
                Set.of());
        MyBatisMethodGeneration between = generate(
                "findByAgeBetweenAndStatus",
                MyBatisSqlDialect.GENERIC,
                Set.of());

        assertEquals(List.of("age", "age2"), names(repeated));
        assertEquals(List.of("ageStart", "ageEnd", "status"), names(between));
        assertTrue(between.xmlStatement().contains(
                "\"age\" BETWEEN #{ageStart,jdbcType=INTEGER} "
                        + "AND #{ageEnd,jdbcType=INTEGER}"));
    }

    @Test
    public void generatesForeachAndLikeBindsWithoutTextSubstitution() throws Exception {
        MyBatisMethodGeneration generation = generate(
                "findByStatusInAndNameContaining",
                MyBatisSqlDialect.H2,
                Set.of());

        assertEquals("java.util.Collection<java.lang.String>",
                generation.parameters().get(0).javaType());
        assertTrue(generation.xmlStatement().contains(
                "<foreach collection=\"statusValues\" item=\"item\""));
        assertTrue(generation.xmlStatement().contains(
                "<bind name=\"namePattern\" value=\"name == null ? null : "
                        + "'%' + name + '%'\"/>"));
        assertTrue(generation.xmlStatement().contains("\"name\" LIKE #{namePattern}"));
        assertFalse(generation.xmlStatement().contains("${"));
        assertTrue(generation.dynamic());
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void keepsOptionalLikeBindsNullSafeBeforeTheirIfGuard() throws Exception {
        MyBatisMethodGeneration generation = generate(
                "findByStatusAndNameContaining",
                MyBatisSqlDialect.H2,
                Set.of(1));

        assertTrue(generation.xmlStatement().contains(
                "<bind name=\"namePattern\" value=\"name == null ? null : "
                        + "'%' + name + '%'\"/>"));
        assertTrue(generation.xmlStatement().contains(
                "<if test=\"name != null\">AND \"name\" LIKE #{namePattern}</if>"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void generatesOptionalAndConditionsWithWhereTrimming() throws Exception {
        MyBatisMethodGeneration generation = generate(
                "findByStatusAndAgeBetween",
                MyBatisSqlDialect.GENERIC,
                Set.of(0, 1));

        assertTrue(generation.xmlStatement().contains("<where>"));
        assertTrue(generation.xmlStatement().contains(
                "<if test=\"status != null\">AND \"status\" "
                        + "= #{status,jdbcType=VARCHAR}</if>"));
        assertTrue(generation.xmlStatement().contains(
                "ageStart != null and ageEnd != null"));
        assertTrue(generation.dynamic());
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void rejectsUnsafeOrInvalidOptionalConditions() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate("findByStatusOrAge", MyBatisSqlDialect.GENERIC, Set.of(0)))
                .getMessage().contains("含 OR"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate("findByActiveTrue", MyBatisSqlDialect.GENERIC, Set.of(0)))
                .getMessage().contains("无参数条件"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate("findByStatus", MyBatisSqlDialect.GENERIC, Set.of(1)))
                .getMessage().contains("超出条件数量"));
    }

    @Test
    public void generatesDialectSpecificFixedLimits() {
        assertTrue(generate("findTop5ByStatus", MyBatisSqlDialect.MYSQL, Set.of())
                .sqlPreview().endsWith("LIMIT 5"));
        assertTrue(generate("findTop5ByStatus", MyBatisSqlDialect.POSTGRESQL, Set.of())
                .sqlPreview().endsWith("LIMIT 5"));
        assertTrue(generate("findTop5ByStatus", MyBatisSqlDialect.DAMENG, Set.of())
                .sqlPreview().endsWith("LIMIT 5"));
        assertTrue(generate("findTop5ByStatus", MyBatisSqlDialect.ORACLE, Set.of())
                .sqlPreview().endsWith("FETCH FIRST 5 ROWS ONLY"));
        assertTrue(generate("findTop5ByStatus", MyBatisSqlDialect.SQL_SERVER, Set.of())
                .sqlPreview().startsWith("SELECT TOP (5)"));
    }

    @Test
    public void generatesPagedParametersAndRequiresSqlServerOrder() {
        MyBatisMethodGeneration mysql = generate(
                "findPagedByStatus", MyBatisSqlDialect.MYSQL, Set.of());
        MyBatisMethodGeneration oracle = generate(
                "findPagedByStatus", MyBatisSqlDialect.ORACLE, Set.of());
        MyBatisMethodGeneration dameng = generate(
                "findPagedByStatus", MyBatisSqlDialect.DAMENG, Set.of());
        MyBatisMethodGeneration sqlServer = generate(
                "findPagedByStatusOrderById",
                MyBatisSqlDialect.SQL_SERVER,
                Set.of());

        assertEquals(List.of("status", "offset", "pageSize"), names(mysql));
        assertTrue(mysql.sqlPreview().endsWith("LIMIT #{pageSize} OFFSET #{offset}"));
        assertTrue(oracle.sqlPreview().endsWith(
                "OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY"));
        assertTrue(dameng.sqlPreview().endsWith(
                "LIMIT #{pageSize} OFFSET #{offset}"));
        assertTrue(sqlServer.sqlPreview().contains(
                "ORDER BY [id] ASC OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate("findPagedByStatus", MyBatisSqlDialect.SQL_SERVER, Set.of()))
                .getMessage().contains("必须显式指定 OrderBy"));
    }

    @Test
    public void generatesScalarOperationsAndBooleanLiterals() {
        assertEquals("long", generate(
                "countDistinctEmailByStatus", MyBatisSqlDialect.GENERIC, Set.of()).returnType());
        assertEquals("boolean", generate(
                "existsByActiveTrue", MyBatisSqlDialect.ORACLE, Set.of()).returnType());
        assertEquals("java.math.BigDecimal", generate(
                "sumBalanceByStatus", MyBatisSqlDialect.GENERIC, Set.of()).returnType());
        assertEquals("java.time.LocalDateTime", generate(
                "maxCreatedAtByStatus", MyBatisSqlDialect.GENERIC, Set.of()).returnType());
        assertTrue(generate("existsByActiveTrue", MyBatisSqlDialect.ORACLE, Set.of())
                .sqlPreview().contains("\"active\" = 1"));
        assertTrue(generate("existsByActiveFalse", MyBatisSqlDialect.POSTGRESQL, Set.of())
                .sqlPreview().contains("\"active\" = FALSE"));
    }

    @Test
    public void preservesTypeHandlerInPlaceholders() {
        MyBatisMethodField coded = new MyBatisMethodField(
                "code", "Code", "code", "com.example.Code",
                Optional.of("com.example.CodeTypeHandler"),
                Types.VARCHAR, false, false, false);
        MyBatisMethodSchema schema = new MyBatisMethodSchema("sample", List.of(coded));

        MyBatisMethodGeneration generation = generate(
                "findByCode", schema, MyBatisSqlDialect.GENERIC, Set.of());

        assertTrue(generation.xmlStatement().contains(
                "jdbcType=VARCHAR,typeHandler=com.example.CodeTypeHandler"));
    }

    @Test
    public void escapesIdentifiersAndXmlOperators() throws Exception {
        MyBatisMethodField order = new MyBatisMethodField(
                "order", "Order", "order", "java.lang.Integer",
                Optional.empty(), Types.INTEGER, false, false, false);
        MyBatisMethodSchema schema = new MyBatisMethodSchema("select", List.of(order));
        MyBatisMethodGeneration generation = generate(
                "findByOrderLessThan", schema, MyBatisSqlDialect.MYSQL, Set.of());

        assertTrue(generation.xmlStatement().contains(
                "FROM `select` WHERE `order` &lt; #{order,jdbcType=INTEGER}"));
        assertTrue(generation.sqlPreview().contains(
                "FROM `select` WHERE `order` < #{order,jdbcType=INTEGER}"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void qualifiesTheSelectedTableForEveryDialect() throws Exception {
        MyBatisMethodSchema qualified = new MyBatisMethodSchema(
                Optional.of("tenant_catalog"),
                Optional.of("audit_schema"),
                "user_account",
                SCHEMA.fields());
        java.util.Map<MyBatisSqlDialect, String> expected = java.util.Map.of(
                MyBatisSqlDialect.GENERIC,
                "\"tenant_catalog\".\"audit_schema\".\"user_account\"",
                MyBatisSqlDialect.MYSQL,
                "`tenant_catalog`.`user_account`",
                MyBatisSqlDialect.POSTGRESQL,
                "\"audit_schema\".\"user_account\"",
                MyBatisSqlDialect.ORACLE,
                "\"audit_schema\".\"user_account\"",
                MyBatisSqlDialect.SQL_SERVER,
                "[tenant_catalog].[audit_schema].[user_account]",
                MyBatisSqlDialect.SQLITE,
                "\"tenant_catalog\".\"user_account\"",
                MyBatisSqlDialect.DAMENG,
                "\"audit_schema\".\"user_account\"",
                MyBatisSqlDialect.H2,
                "\"tenant_catalog\".\"audit_schema\".\"user_account\"");

        for (MyBatisSqlDialect dialect : MyBatisSqlDialect.values()) {
            MyBatisMethodGeneration generation = generate(
                    "findById", qualified, dialect, Set.of());
            assertTrue(dialect + ": " + generation.xmlStatement(),
                    generation.xmlStatement().contains(" FROM " + expected.get(dialect)));
            assertWellFormed(generation.xmlStatement());
        }
    }

    @Test
    public void keepsSameNamedTablesInDifferentSchemasDistinct() {
        MyBatisMethodSchema audit = new MyBatisMethodSchema(
                Optional.empty(), Optional.of("audit"), "events", SCHEMA.fields());
        MyBatisMethodSchema archive = new MyBatisMethodSchema(
                Optional.empty(), Optional.of("archive"), "events", SCHEMA.fields());

        String auditSql = generate(
                "findById", audit, MyBatisSqlDialect.POSTGRESQL, Set.of()).sqlPreview();
        String archiveSql = generate(
                "findById", archive, MyBatisSqlDialect.POSTGRESQL, Set.of()).sqlPreview();

        assertTrue(auditSql.contains("FROM \"audit\".\"events\""));
        assertTrue(archiveSql.contains("FROM \"archive\".\"events\""));
        assertFalse(auditSql.equals(archiveSql));
    }

    @Test
    public void qualifiesBatchInsertWithoutExecutingAnEmptyCollectionFallback()
            throws Exception {
        MyBatisMethodSchema qualified = new MyBatisMethodSchema(
                Optional.empty(), Optional.of("audit"), "events", SCHEMA.fields());

        MyBatisMethodGeneration generation = generate(
                "insertBatch", qualified, MyBatisSqlDialect.POSTGRESQL, Set.of());

        assertTrue(generation.xmlStatement().contains(
                "INSERT INTO \"audit\".\"events\""));
        assertTrue(generation.xmlStatement().contains(
                "@java.util.Collections@enumeration("
                        + "@java.util.Objects@requireNonNull(entities))"));
        assertFalse(generation.xmlStatement().contains("WHERE 1 = 0"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void quotesEachQualifiedPartAndRejectsUnsafeRawNames() throws Exception {
        MyBatisMethodSchema quoted = new MyBatisMethodSchema(
                Optional.empty(), Optional.of("tenant\"audit"), "user&account", SCHEMA.fields());
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", quoted);

        MyBatisMethodGeneration escaped = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        quoted,
                        parsed.query(),
                        MyBatisSqlDialect.POSTGRESQL,
                        "com.example.UserAccount",
                        true,
                        Set.of()));

        assertTrue(escaped.xmlStatement().contains(
                "FROM \"tenant\"\"audit\".\"user&amp;account\""));
        assertWellFormed(escaped.xmlStatement());
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                quoted,
                                parsed.query(),
                                MyBatisSqlDialect.POSTGRESQL,
                                "com.example.UserAccount",
                                false,
                                Set.of())))
                .getMessage().contains("普通安全名称"));

        MyBatisMethodField quotedColumn = field(
                "note", "Note", "say\"hi", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema quotedColumnSchema = new MyBatisMethodSchema(
                "events", List.of(quotedColumn));
        MyBatisMethodParseResult.Success columnParsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findByNote", quotedColumnSchema);
        MyBatisMethodGeneration escapedColumn = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        quotedColumnSchema,
                        columnParsed.query(),
                        MyBatisSqlDialect.POSTGRESQL,
                        "com.example.Event",
                        true,
                        Set.of()));

        assertTrue(escapedColumn.xmlStatement().contains(
                "\"say\"\"hi\" = #{note,jdbcType=VARCHAR}"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                quotedColumnSchema,
                                columnParsed.query(),
                                MyBatisSqlDialect.POSTGRESQL,
                                "com.example.Event",
                                false,
                                Set.of())))
                .getMessage().contains("普通安全名称"));
    }

    @Test
    public void escapesXmlForbiddenCdataTerminatorInSqlServerIdentifiers() throws Exception {
        MyBatisMethodField column = field(
                "id", "Id", "id]>value", "java.lang.Long", Types.BIGINT);
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                Optional.of("catalog]>name"),
                Optional.of("schema]>name"),
                "table]>name",
                List.of(column));

        MyBatisMethodGeneration generation = generate(
                "findById", schema, MyBatisSqlDialect.SQL_SERVER, Set.of());

        assertFalse(generation.xmlStatement().contains("]]>"));
        assertTrue(generation.xmlStatement().contains("]]&gt;"));
        assertTrue(generation.sqlPreview().contains(
                "[catalog]]>name].[schema]]>name].[table]]>name]"));
        assertTrue(generation.sqlPreview().contains("[id]]>value]"));
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void rejectsMyBatisSubstitutionTokensInsideQuotedIdentifiers() {
        MyBatisMethodSchema tokenTable = new MyBatisMethodSchema(
                "users${status}", List.of(SCHEMA.fields().getFirst()));
        IllegalArgumentException tableFailure = assertThrows(
                IllegalArgumentException.class,
                () -> generate("findById", tokenTable, MyBatisSqlDialect.POSTGRESQL,
                        Set.of()));
        assertTrue(tableFailure.getMessage().contains("动态替换令牌"));

        MyBatisMethodField tokenColumn = field(
                "id", "Id", "id#{value}", "java.lang.Long", Types.BIGINT);
        MyBatisMethodSchema tokenColumnSchema = new MyBatisMethodSchema(
                "users", List.of(tokenColumn));
        IllegalArgumentException columnFailure = assertThrows(
                IllegalArgumentException.class,
                () -> generate("findById", tokenColumnSchema,
                        MyBatisSqlDialect.POSTGRESQL, Set.of()));
        assertTrue(columnFailure.getMessage().contains("动态替换令牌"));
    }

    @Test
    public void rejectsUnescapedSqlKeywords() {
        MyBatisMethodSchema keywordTable = new MyBatisMethodSchema(
                "user", List.of(SCHEMA.fields().getFirst()));
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", keywordTable);
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisMethodSqlGenerator.generate(
                        new MyBatisMethodGenerationRequest(
                                keywordTable, parsed.query(), MyBatisSqlDialect.GENERIC,
                                "com.example.User", false, Set.of())))
                .getMessage().contains("普通安全名称"));
    }

    private static MyBatisMethodGeneration generate(
            String methodName,
            MyBatisSqlDialect dialect,
            Set<Integer> optionalConditions) {
        return generate(methodName, SCHEMA, dialect, optionalConditions);
    }

    private static MyBatisMethodGeneration generate(
            String methodName,
            MyBatisMethodSchema schema,
            MyBatisSqlDialect dialect,
            Set<Integer> optionalConditions) {
        MyBatisMethodParseResult result = MyBatisMethodNameParser.parse(methodName, schema);
        assertTrue("方法名解析失败：" + result,
                result instanceof MyBatisMethodParseResult.Success);
        MyBatisMethodQuery query = ((MyBatisMethodParseResult.Success) result).query();
        return MyBatisMethodSqlGenerator.generate(new MyBatisMethodGenerationRequest(
                schema,
                query,
                dialect,
                "com.example.UserAccount",
                true,
                optionalConditions));
    }

    private static List<String> names(MyBatisMethodGeneration generation) {
        return generation.parameters().stream().map(MyBatisMethodParameter::name).toList();
    }

    private static MyBatisMethodField field(
            String property,
            String token,
            String column,
            String javaType,
            int jdbcType) {
        return new MyBatisMethodField(
                property, token, column, javaType, Optional.empty(), jdbcType,
                true, false, false);
    }

    private static void assertWellFormed(String statement) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<mapper>" + statement + "</mapper>").getBytes(StandardCharsets.UTF_8)));
    }
}
