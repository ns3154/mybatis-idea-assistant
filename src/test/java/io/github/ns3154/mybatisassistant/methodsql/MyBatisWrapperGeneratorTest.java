package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.model.MyBatisEntityKind;
import io.github.ns3154.mybatisassistant.model.MyBatisEntityModel;
import io.github.ns3154.mybatisassistant.model.MyBatisFrameworkKind;
import io.github.ns3154.mybatisassistant.model.MyBatisFrameworkMapperBinding;
import org.junit.Test;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertTrue(generation.code().indexOf("status == null || age == null")
                < generation.code().indexOf("QueryWrapper"));
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
                ".likeLeft(\"name\", name, name != null).eq(\"status\", status, true)"));
    }

    @Test
    public void preservesOrOfAndGroups() {
        MyBatisWrapperGeneration generation = generate(
                "findByStatusOrAgeGreaterThanAndActiveTrue",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of());
        MyBatisWrapperGeneration flex = generate(
                "findByStatusOrAgeGreaterThanAndActiveTrue",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertTrue(generation.code().contains(
                "wrapper.eq(\"status\", status).or(group1 -> group1"
                        + ".gt(\"age\", age).eq(\"active\", true));"));
        assertTrue(flex.code().contains(
                "wrapper.eq(\"status\", status, true)"
                        + ".or((java.util.function.Consumer<"
                        + "com.mybatisflex.core.query.QueryWrapper>) group1 -> group1"
                        + ".gt(\"age\", age, true).eq(\"active\", true, true));"));
    }

    @Test
    public void allocatesLocalAndLambdaNamesAroundMethodParameters() {
        MyBatisMethodSchema collidingSchema = new MyBatisMethodSchema(
                "user_account",
                List.of(
                        field(
                                "wrapper",
                                "Wrapper",
                                "wrapper_value",
                                "java.lang.String",
                                Types.VARCHAR),
                        field(
                                "group1",
                                "Group1",
                                "group_value",
                                "java.lang.String",
                                Types.VARCHAR),
                        field(
                                "active",
                                "Active",
                                "active",
                                "java.lang.Boolean",
                                Types.BOOLEAN)));

        MyBatisWrapperGeneration plus = generate(
                "findByWrapperOrGroup1AndActiveTrue",
                collidingSchema,
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of());
        MyBatisWrapperGeneration flex = generate(
                "findByWrapperOrGroup1AndActiveTrue",
                collidingSchema,
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertEquals("wrapper2", plus.variableName());
        assertTrue(plus.code().contains("QueryWrapper<com.example.User> wrapper2 ="));
        assertTrue(plus.code().contains(
                "wrapper2.eq(\"wrapper_value\", wrapper)"
                        + ".or(group2 -> group2.eq(\"group_value\", group1)"
                        + ".eq(\"active\", true));"));
        assertEquals("wrapper2", flex.variableName());
        assertTrue(flex.code().contains("QueryWrapper wrapper2 ="));
        assertTrue(flex.code().contains(
                "wrapper2.eq(\"wrapper_value\", wrapper, true)"
                        + ".or((java.util.function.Consumer<"
                        + "com.mybatisflex.core.query.QueryWrapper>) group2 -> group2"
                        + ".eq(\"group_value\", group1, true)"
                        + ".eq(\"active\", true, true));"));
    }

    @Test
    public void usesExplicitTrueForEveryRequiredFlexCondition() {
        MyBatisWrapperGeneration generation = generate(
                "findByNameAndAgeBetweenAndActiveTrue",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertTrue(generation.code().contains(".eq(\"name\", name, true)"));
        assertTrue(generation.code().contains(
                ".between(\"age\", ageStart, ageEnd, true)"));
        assertTrue(generation.code().contains(".eq(\"active\", true, true)"));
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
    public void rejectsReadonlyUpdateSubjectsBeforeGeneratingWrapper() {
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
        MyBatisMethodGeneration unusedMethod = new MyBatisMethodGeneration(
                "int", List.of(), "", "", "", false);

        for (MyBatisMethodField readonly : List.of(autoIncrementId, generatedDigest)) {
            MyBatisMethodQuery forged = new MyBatisMethodQuery(
                    "update" + readonly.methodToken() + "ByName",
                    MyBatisMethodOperation.UPDATE,
                    List.of(readonly),
                    false,
                    java.util.OptionalInt.empty(),
                    false,
                    false,
                    Optional.of(new MyBatisMethodCondition(
                            name, MyBatisMethodComparison.EQUALS)),
                    List.of());

            assertTrue(assertThrows(
                    IllegalArgumentException.class,
                    () -> MyBatisWrapperGenerator.generate(
                            new MyBatisWrapperGenerationRequest(
                                    schema,
                                    forged,
                                    unusedMethod,
                                    MyBatisWrapperFramework.MYBATIS_PLUS,
                                    "3.5.17",
                                    "com.example.User",
                                    MyBatisSqlDialect.H2,
                                    true,
                                    Set.of())))
                    .getMessage().contains("唯一解析"));
        }
    }

    @Test
    public void rejectsDuplicateUpdateSubjectsBeforeGeneratingWrapper() {
        MyBatisMethodField id = SCHEMA.fields().getFirst();
        MyBatisMethodField email = field(
                "email", "Email", "email", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "user_account", List.of(id, email));
        MyBatisMethodQuery forged = new MyBatisMethodQuery(
                "updateEmailAndEmailById",
                MyBatisMethodOperation.UPDATE,
                List.of(email, email),
                false,
                java.util.OptionalInt.empty(),
                false,
                false,
                Optional.of(new MyBatisMethodCondition(
                        id, MyBatisMethodComparison.EQUALS)),
                List.of());
        MyBatisMethodGeneration unusedMethod = new MyBatisMethodGeneration(
                "int", List.of(), "", "", "", false);

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisWrapperGenerator.generate(
                        new MyBatisWrapperGenerationRequest(
                                schema,
                                forged,
                                unusedMethod,
                                MyBatisWrapperFramework.MYBATIS_PLUS,
                                "3.5.17",
                                "com.example.User",
                                MyBatisSqlDialect.H2,
                                true,
                                Set.of())))
                .getMessage().contains("唯一解析"));
    }

    @Test
    public void generatesFlexProjectionOrderAndLimitUsingNativeApis() {
        MyBatisWrapperGeneration generation = generate(
                "findDistinctTop5NameByStatusOrderByIdDesc",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of());

        assertTrue(generation.code().contains(
                "QueryWrapper.create().from(new "
                        + "com.mybatisflex.core.query.RawQueryTable(\"user_account\"))"));
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
    public void guardsRequiredWrapperParametersBeforeAnyFrameworkCall() {
        for (MyBatisWrapperFramework framework : MyBatisWrapperFramework.values()) {
            String version = framework == MyBatisWrapperFramework.MYBATIS_PLUS
                    ? "3.5.17" : "1.11.8";
            MyBatisWrapperGeneration collection = generate(
                    "findByIdIn", framework, version, Set.of());
            MyBatisWrapperGeneration range = generate(
                    "findByAgeBetween", framework, version, Set.of());

            assertTrue(collection.code().startsWith(
                    "if (idValues == null || idValues.isEmpty() || "
                            + "java.util.Collections.frequency(idValues, null) != 0)"));
            assertTrue(collection.code().indexOf("if (")
                    < collection.code().indexOf("wrapper ="));
            assertTrue(range.code().startsWith(
                    "if (ageStart == null || ageEnd == null)"));
        }
    }

    @Test
    public void rejectsNullElementsButAllowsOptionalNullOrEmptyCollectionsToOmit() {
        MyBatisWrapperGeneration plus = generate(
                "findByIdInAndStatus",
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                Set.of(0));
        MyBatisWrapperGeneration flex = generate(
                "findByIdInAndStatus",
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                Set.of(0));

        for (MyBatisWrapperGeneration generation : List.of(plus, flex)) {
            assertTrue(generation.code().startsWith(
                    "if (idValues != null && !idValues.isEmpty() && "
                            + "java.util.Collections.frequency(idValues, null) != 0 "
                            + "|| status == null)"));
            assertTrue(generation.code().contains(
                    "idValues != null && !idValues.isEmpty() && "
                            + "java.util.Collections.frequency(idValues, null) == 0"));
        }
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
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "deleteById", MyBatisWrapperFramework.MYBATIS_FLEX, "1.11.8", Set.of()))
                .getMessage().contains("删除 QueryWrapper"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "insertBatch", MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17", Set.of()))
                .getMessage().contains("批量插入"));
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

        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("updateStatusById", SCHEMA);
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        parsed.query(),
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        false,
                        Set.of()));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisWrapperGenerator.generate(
                        new MyBatisWrapperGenerationRequest(
                                SCHEMA,
                                parsed.query(),
                                method,
                                MyBatisWrapperFramework.MYBATIS_PLUS,
                                "3.5.17",
                                "com.example.User",
                                Set.of(0))))
                .getMessage().contains("全部谓词"));
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
                        false,
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

    @Test
    public void rejectsForgedWrapperAstAndMismatchedMethodGeneration() {
        MyBatisMethodQuery canonical = ((MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", SCHEMA)).query();
        MyBatisMethodQuery forged = new MyBatisMethodQuery(
                canonical.methodName(),
                MyBatisMethodOperation.DELETE,
                List.of(),
                false,
                java.util.OptionalInt.empty(),
                false,
                false,
                Optional.empty(),
                List.of());
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        canonical,
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        false,
                        Set.of()));

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisWrapperGenerator.generate(
                        new MyBatisWrapperGenerationRequest(
                                SCHEMA,
                                forged,
                                method,
                                MyBatisWrapperFramework.MYBATIS_PLUS,
                                "3.5.17",
                                "com.example.User",
                                MyBatisSqlDialect.GENERIC,
                                false,
                                Set.of())))
                .getMessage().contains("唯一解析"));

        MyBatisMethodGeneration wrongPolicy = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        canonical,
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        true,
                        Set.of()));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisWrapperGenerator.generate(
                        new MyBatisWrapperGenerationRequest(
                                SCHEMA,
                                canonical,
                                wrongPolicy,
                                MyBatisWrapperFramework.MYBATIS_PLUS,
                                "3.5.17",
                                "com.example.User",
                                MyBatisSqlDialect.GENERIC,
                                false,
                                Set.of())))
                .getMessage().contains("不一致"));
    }

    @Test
    public void createsWrapperRequestFromUnifiedFrameworkBinding() {
        MyBatisMethodParseResult.Success parsed = (MyBatisMethodParseResult.Success)
                MyBatisMethodNameParser.parse("findById", SCHEMA);
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        SCHEMA,
                        parsed.query(),
                        MyBatisSqlDialect.GENERIC,
                        "com.example.User",
                        false,
                        Set.of()));

        MyBatisWrapperGenerationRequest request =
                MyBatisWrapperGenerationRequest.fromFrameworkBinding(
                        SCHEMA,
                        parsed.query(),
                        method,
                        binding(MyBatisFrameworkKind.MYBATIS_PLUS),
                        "3.5.17",
                        Set.of());

        assertEquals(MyBatisWrapperFramework.MYBATIS_PLUS, request.framework());
        assertEquals("com.example.User", request.entityType());
        assertEquals(MyBatisSqlDialect.GENERIC, request.dialect());
        assertFalse(request.escapeIdentifiers());
        assertTrue(MyBatisWrapperGenerator.generate(request).code()
                .contains("QueryWrapper<com.example.User>"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisWrapperGenerationRequest.fromFrameworkBinding(
                        SCHEMA,
                        parsed.query(),
                        method,
                        binding(MyBatisFrameworkKind.TK_MAPPER),
                        "6.0.0",
                        Set.of()))
                .getMessage().contains("暂不支持 Wrapper"));
    }

    @Test
    public void bindsFlexToTheSelectedSchemaTable() {
        MyBatisMethodSchema audit = new MyBatisMethodSchema(
                Optional.of("connected_catalog"),
                Optional.of("audit"),
                "user_account",
                SCHEMA.fields());
        MyBatisMethodSchema archive = new MyBatisMethodSchema(
                Optional.of("connected_catalog"),
                Optional.of("archive"),
                "user_account",
                SCHEMA.fields());

        MyBatisWrapperGeneration auditWrapper = generate(
                "findById",
                audit,
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.POSTGRESQL,
                true,
                Set.of());
        MyBatisWrapperGeneration archiveWrapper = generate(
                "findById",
                archive,
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.POSTGRESQL,
                true,
                Set.of());

        assertTrue(auditWrapper.code().contains(
                "RawQueryTable(\"\\\"audit\\\".\\\"user_account\\\"\")"));
        assertTrue(archiveWrapper.code().contains(
                "RawQueryTable(\"\\\"archive\\\".\\\"user_account\\\"\")"));

        MyBatisWrapperGeneration mysql = generate(
                "findById",
                new MyBatisMethodSchema(
                        Optional.of("tenant_db"),
                        Optional.of("ignored_schema"),
                        "user_account",
                        SCHEMA.fields()),
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.MYSQL,
                true,
                Set.of());
        MyBatisWrapperGeneration sqlServer = generate(
                "findById",
                new MyBatisMethodSchema(
                        Optional.of("tenant_catalog"),
                        Optional.of("audit"),
                        "user_account",
                        SCHEMA.fields()),
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.SQL_SERVER,
                true,
                Set.of());
        assertTrue(mysql.code().contains(
                "RawQueryTable(\"`tenant_db`.`user_account`\")"));
        assertTrue(sqlServer.code().contains(
                "RawQueryTable(\"[tenant_catalog].[audit].[user_account]\")"));
    }

    @Test
    public void failsClosedWhenAWrapperCannotProveTheQualifiedTable() {
        MyBatisMethodSchema qualified = new MyBatisMethodSchema(
                Optional.empty(), Optional.of("audit"), "user_account", SCHEMA.fields());
        MyBatisMethodSchema catalogQualified = new MyBatisMethodSchema(
                Optional.of("tenant"), Optional.empty(), "user_account", SCHEMA.fields());

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById",
                        qualified,
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17",
                        Set.of()))
                .getMessage().contains("无法证明"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById",
                        catalogQualified,
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17",
                        Set.of()))
                .getMessage().contains("无法证明"));

        MyBatisMethodSchema keywordTable = new MyBatisMethodSchema(
                "order", SCHEMA.fields());
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> generate(
                        "findById",
                        keywordTable,
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17",
                        MyBatisSqlDialect.MYSQL,
                        true,
                        Set.of()))
                .getMessage().contains("安全引用"));
    }

    @Test
    public void handlesKeywordAndQuotedColumnsAccordingToWrapperCapabilities() {
        MyBatisMethodField keyword = field(
                "order", "Order", "order", "java.lang.Integer", Types.INTEGER);
        MyBatisMethodField quoted = field(
                "note", "Note", "say\"hi", "java.lang.String", Types.VARCHAR);
        MyBatisMethodSchema keywordSchema = new MyBatisMethodSchema(
                "events", List.of(keyword));
        MyBatisMethodSchema quotedSchema = new MyBatisMethodSchema(
                "events", List.of(quoted));

        MyBatisWrapperGeneration plusKeyword = generate(
                "findByOrder",
                keywordSchema,
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                MyBatisSqlDialect.MYSQL,
                true,
                Set.of());
        MyBatisWrapperGeneration plusQuoted = generate(
                "findByNote",
                quotedSchema,
                MyBatisWrapperFramework.MYBATIS_PLUS,
                "3.5.17",
                MyBatisSqlDialect.POSTGRESQL,
                true,
                Set.of());
        MyBatisWrapperGeneration flexKeyword = generate(
                "findByOrder",
                keywordSchema,
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.POSTGRESQL,
                true,
                Set.of());

        assertTrue(plusKeyword.code().contains("wrapper.eq(\"`order`\", order);"));
        assertTrue(plusQuoted.code().contains(
                "wrapper.eq(\"\\\"say\\\"\\\"hi\\\"\", note);"));
        MyBatisWrapperGeneration flexQuoted = generate(
                "findByNote",
                quotedSchema,
                MyBatisWrapperFramework.MYBATIS_FLEX,
                "1.11.8",
                MyBatisSqlDialect.POSTGRESQL,
                true,
                Set.of());
        assertTrue(flexKeyword.code().contains(
                "wrapper.eq(\"\\\"order\\\"\", order, true);"));
        assertTrue(flexQuoted.code().contains(
                "wrapper.eq(\"\\\"say\\\"\\\"hi\\\"\", note, true);"));
    }

    private static MyBatisWrapperGeneration generate(
            String methodName,
            MyBatisWrapperFramework framework,
            String version,
            Set<Integer> optionalConditions) {
        return generate(methodName, SCHEMA, framework, version, optionalConditions);
    }

    private static MyBatisWrapperGeneration generate(
            String methodName,
            MyBatisMethodSchema schema,
            MyBatisWrapperFramework framework,
            String version,
            Set<Integer> optionalConditions) {
        return generate(
                methodName,
                schema,
                framework,
                version,
                MyBatisSqlDialect.GENERIC,
                false,
                optionalConditions);
    }

    private static MyBatisWrapperGeneration generate(
            String methodName,
            MyBatisMethodSchema schema,
            MyBatisWrapperFramework framework,
            String version,
            MyBatisSqlDialect dialect,
            boolean escapeIdentifiers,
            Set<Integer> optionalConditions) {
        MyBatisMethodParseResult result = MyBatisMethodNameParser.parse(methodName, schema);
        assertTrue(result instanceof MyBatisMethodParseResult.Success);
        MyBatisMethodQuery query = ((MyBatisMethodParseResult.Success) result).query();
        MyBatisMethodGeneration method = MyBatisMethodSqlGenerator.generate(
                new MyBatisMethodGenerationRequest(
                        schema,
                        query,
                        dialect,
                        "com.example.User",
                        escapeIdentifiers,
                        optionalConditions));
        return MyBatisWrapperGenerator.generate(new MyBatisWrapperGenerationRequest(
                schema,
                query,
                method,
                framework,
                version,
                "com.example.User",
                dialect,
                escapeIdentifiers,
                optionalConditions));
    }

    private static MyBatisFrameworkMapperBinding binding(MyBatisFrameworkKind framework) {
        return new MyBatisFrameworkMapperBinding(
                framework,
                new MyBatisEntityModel(
                        "com.example.User",
                        MyBatisEntityKind.CLASS,
                        "com.example.User",
                        List.of(),
                        false),
                List.of());
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
