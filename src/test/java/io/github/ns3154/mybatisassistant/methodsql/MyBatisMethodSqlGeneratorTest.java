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
        assertFalse(generation.dynamic());
        assertWellFormed(generation.xmlStatement());
    }

    @Test
    public void derivesSingleAndProjectedReturnTypes() {
        assertEquals("java.util.Optional<com.example.UserAccount>",
                generate("getById", MyBatisSqlDialect.GENERIC, Set.of()).returnType());
        assertEquals("java.util.List<java.lang.String>",
                generate("findEmailByStatus", MyBatisSqlDialect.GENERIC, Set.of()).returnType());
        assertEquals(
                "java.util.List<java.util.Map<java.lang.String, java.lang.Object>>",
                generate("findNameAndEmailByStatus", MyBatisSqlDialect.GENERIC, Set.of())
                        .returnType());
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
                "<bind name=\"namePattern\" value=\"'%' + name + '%'\"/>"));
        assertTrue(generation.xmlStatement().contains("\"name\" LIKE #{namePattern}"));
        assertFalse(generation.xmlStatement().contains("${"));
        assertTrue(generation.dynamic());
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
        MyBatisMethodGeneration sqlServer = generate(
                "findPagedByStatusOrderById",
                MyBatisSqlDialect.SQL_SERVER,
                Set.of());

        assertEquals(List.of("status", "offset", "pageSize"), names(mysql));
        assertTrue(mysql.sqlPreview().endsWith("LIMIT #{pageSize} OFFSET #{offset}"));
        assertTrue(oracle.sqlPreview().endsWith(
                "OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY"));
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
