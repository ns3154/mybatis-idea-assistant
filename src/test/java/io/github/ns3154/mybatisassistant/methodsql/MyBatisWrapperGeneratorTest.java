package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.junit.Test;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MyBatisWrapperGeneratorTest {
    private static final MyBatisMethodSchema SCHEMA = new MyBatisMethodSchema(
            "user_account",
            List.of(
                    field("id", "Id", "id", "java.lang.Long", Types.BIGINT),
                    field("name", "Name", "name", "java.lang.String", Types.VARCHAR),
                    field("status", "Status", "status", "java.lang.String", Types.VARCHAR),
                    field("age", "Age", "age", "java.lang.Integer", Types.INTEGER),
                    field("active", "Active", "active", "java.lang.Boolean", Types.BOOLEAN)));

    @Test
    public void generatesMyBatisPlusQueryWrapper() {
        MyBatisWrapperGeneration generation = generate(
                "findNameByStatusAndAgeGreaterThanOrderByIdDesc",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of());

        assertEquals(
                "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.example.User>",
                generation.wrapperType());
        assertTrue(generation.code().contains(
                "new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>()"));
        assertTrue(generation.code().contains("wrapper.select(\"name\");"));
        assertTrue(generation.code().contains(
                "wrapper.eq(\"status\", status).gt(\"age\", age);"));
        assertTrue(generation.code().contains("wrapper.orderByDesc(\"id\");"));
    }

    @Test
    public void generatesFrameworkSpecificOptionalAndLikeCalls() {
        MyBatisWrapperGeneration plus = generate(
                "findByNameStartingWithAndStatus",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of(0));
        MyBatisWrapperGeneration flex = generate(
                "findByNameStartingWithAndStatus",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of(0));

        assertTrue(plus.code().contains(
                ".likeRight(name != null, \"name\", name).eq(\"status\", status)"));
        assertTrue(flex.code().contains(
                ".likeLeft(\"name\", name, name != null).eq(\"status\", status)"));
    }

    @Test
    public void preservesOrOfAndGroups() {
        MyBatisWrapperGeneration generation = generate(
                "findByStatusOrAgeGreaterThanAndActiveTrue",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of());

        assertTrue(generation.code().contains(
                "wrapper.eq(\"status\", status).or(group1 -> group1"
                        + ".gt(\"age\", age).eq(\"active\", true));"));
    }

    @Test
    public void generatesPlusUpdateWrapper() {
        MyBatisWrapperGeneration generation = generate(
                "updateStatusAndNameById",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of());

        assertEquals(
                "com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.example.User>",
                generation.wrapperType());
        assertTrue(generation.code().contains(
                "wrapper.set(\"status\", newStatus).set(\"name\", newName);"));
        assertTrue(generation.code().contains("wrapper.eq(\"id\", id);"));
    }

    @Test
    public void generatesFlexProjectionOrderAndLimitUsingNativeApis() {
        MyBatisWrapperGeneration generation = generate(
                "findDistinctTop5NameByStatusOrderByIdDesc",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertTrue(generation.code().contains(
                "QueryWrapper.create().from(\"user_account\")"));
        assertTrue(generation.code().contains(
                "QueryMethods.distinct(com.mybatisflex.core.query.QueryMethods.column(\"name\"))"));
        assertTrue(generation.code().contains(
                "wrapper.orderBy(com.mybatisflex.core.query.QueryMethods.column(\"id\").desc());"));
        assertTrue(generation.code().contains("wrapper.limit(5);"));
    }

    @Test
    public void generatesFlexPageWithExplicitParameters() {
        MyBatisWrapperGeneration generation = generate(
                "findPagedByStatusOrderById",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertTrue(generation.code().contains("wrapper.limit(pageSize).offset(offset);"));
    }

    @Test
    public void rejectsUnsupportedFrameworkVersionsAndOperations() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById", MyBatisWrapperFramework.MYBATIS_PLUS, "3.4.3", Set.of()))
                .getMessage().contains("不支持"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById", MyBatisWrapperFramework.MYBATIS_FLEX, "1.7.1", Set.of()))
                .getMessage().contains("不支持"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById", MyBatisWrapperFramework.MYBATIS_PLUS, "latest", Set.of()))
                .getMessage().contains("版本格式"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findPagedById", MyBatisWrapperFramework.MYBATIS_PLUS, "3.5.17", Set.of()))
                .getMessage().contains("显式传入 Page"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "updateStatusById", MyBatisWrapperFramework.MYBATIS_FLEX, "1.11.8", Set.of()))
                .getMessage().contains("暂不生成 QueryWrapper"));
    }

    @Test
    public void rejectsInvalidOptionalWrapperConditions() {
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findByActiveTrue", MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17", Set.of(0)))
                .getMessage().contains("无参数"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findByStatusOrAgeGreaterThan", MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17", Set.of(0)))
                .getMessage().contains("OR"));
    }

    @Test
    public void rejectsUnsafeEntityType() {
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", SCHEMA);

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        parsed.query(),
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User;System.exit(1)",
                        true,
                        Set.of()))
                .getMessage().contains("Java 全限定名"));
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        parsed.query(),
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        true,
                        Set.of()));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> new MyBatisWrapperGenerationRequest(
                        SCHEMA,
                        parsed.query(),
                        method,
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17",
                        "com.example.User<String>",
                        Set.of()))
                .getMessage().contains("Java 全限定名"));
    }

    private static MyBatisWrapperGeneration generate(
            String methodName,
            MyBatisWrapperFramework framework,
            String version,
            Set<Integer> optionalConditions) {
        MyBatisMethodParseResult result = MyBatisMethodNameParser.parse(methodName, SCHEMA);
        assertTrue(result instanceof MyBatisMethodParseResult.Success);
        MyBatisMethodQuery query = ((MyBatisMethodParseResult.Success) result).query();
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        query,
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        true,
                        optionalConditions));
        return MyBatisWrapperGenerator.generate(new MyBatisWrapperGenerationRequest(
                SCHEMA,
                query,
                method,
                framework,
                version,
                "com.example.User",
                optionalConditions));
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
}
