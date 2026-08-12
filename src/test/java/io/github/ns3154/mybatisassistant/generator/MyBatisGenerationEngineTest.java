package io.github.ns3154.mybatisassistant.generator;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseObjectKind;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MyBatisGenerationEngineTest extends BasePlatformTestCase {
    public void testGeneratesStandardEntityMapperXmlAndService() {
        MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                "com.example.demo",
                "src/main/java",
                "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                MyBatisGenerationTemplateGroup.STANDARD,
                "t_",
                "Entity",
                true,
                true,
                Set.of("deleted_at"),
                Map.of("payload", new MyBatisGenerationColumnOverride(
                        Optional.of("content"),
                        Optional.of("com.example.JsonValue"),
                        Optional.of("com.example.JsonTypeHandler"))));
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.of("public"),
                "t_order",
                Optional.of("订单 */ 主表"),
                java.util.List.of(
                        column("id", Types.BIGINT, false, true, true, 1, "主键"),
                        column("user", Types.VARCHAR, false, false, false, 2, "用户"),
                        column("payload", Types.OTHER, true, false, false, 3, "JSON"),
                        column("created_at", Types.TIMESTAMP, false, false, false, 4, "创建时间"),
                        column("deleted_at", Types.TIMESTAMP, true, false, false, 5, "删除时间")));

        MyBatisGenerationBundle bundle = MyBatisGenerationEngine.generate(
                new MyBatisGenerationRequest(
                        "main", MyBatisSqlDialect.POSTGRESQL, table, configuration));

        assertEquals("OrderEntity", bundle.entityName());
        assertSize(4, bundle.artifacts());
        String entity = artifact(bundle, MyBatisGenerationArtifactKind.ENTITY).content();
        assertTrue(entity.contains("package com.example.demo.entity;"));
        assertTrue(entity.contains("import com.example.JsonValue;"));
        assertTrue(entity.contains("private Long id;"));
        assertTrue(entity.contains("private String user;"));
        assertTrue(entity.contains("private JsonValue content;"));
        assertTrue(entity.contains("private LocalDateTime createdAt;"));
        assertFalse(entity.contains("deletedAt"));
        assertTrue(entity.contains("订单 * / 主表"));

        String mapper = artifact(bundle, MyBatisGenerationArtifactKind.MAPPER).content();
        assertTrue(mapper.contains("OrderEntity selectByPrimaryKey(Long id);"));
        assertTrue(mapper.contains("int updateByPrimaryKey(OrderEntity entity);"));

        String xml = artifact(bundle, MyBatisGenerationArtifactKind.XML).content();
        assertTrue(xml.contains("FROM \"public\".\"t_order\""));
        assertTrue(xml.contains("\"user\""));
        assertTrue(xml.contains("column=\"payload\" property=\"content\""));
        assertTrue(xml.contains("typeHandler=\"com.example.JsonTypeHandler\""));
        assertTrue(xml.contains("useGeneratedKeys=\"true\" keyProperty=\"id\""));
        assertFalse(xml.contains("deleted_at"));
        assertTrue(xml.contains("#{content,jdbcType=OTHER,typeHandler=com.example.JsonTypeHandler}"));

        String service = artifact(bundle, MyBatisGenerationArtifactKind.SERVICE).content();
        assertTrue(service.contains("public OrderEntity findByPrimaryKey(Long id)"));
        assertTrue(service.contains("return mapper.deleteByPrimaryKey(id);"));
    }

    public void testSanitizesEveryConsecutiveHyphenAndIllegalXmlCommentCharacter()
            throws Exception {
        for (String comment : java.util.List.of(
                "--", "---", "----", "尾随-", "控制" + (char) 1 + "符")) {
            MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                    Optional.empty(),
                    Optional.empty(),
                    "comment_sample",
                    Optional.of(comment),
                    java.util.List.of(column(
                            "id", Types.BIGINT, false, true, false, 1, null)));

            String xml = artifact(
                    generate(table, MyBatisGenerationConfiguration.standard("com.example")),
                    MyBatisGenerationArtifactKind.XML).content();

            assertWellFormedXml(xml);
        }
    }

    public void testCompositeAndMissingPrimaryKeysGenerateConservativeCrud() {
        MyBatisGenerationConfiguration configuration = MyBatisGenerationConfiguration
                .standard("com.example");
        MyBatisDatabaseTable composite = table(
                "tenant_user",
                column("tenant_id", Types.BIGINT, false, true, false, 1, null),
                column("user_id", Types.BIGINT, false, true, false, 2, null),
                column("name", Types.VARCHAR, true, false, false, 3, null));
        MyBatisGenerationBundle compositeBundle = generate(composite, configuration);
        String mapper = artifact(compositeBundle, MyBatisGenerationArtifactKind.MAPPER).content();
        assertTrue(mapper.contains("@Param(\"tenantId\") Long tenantId"));
        assertTrue(mapper.contains("@Param(\"userId\") Long userId"));
        String xml = artifact(compositeBundle, MyBatisGenerationArtifactKind.XML).content();
        assertTrue(xml.contains("\"tenant_id\" = #{tenantId,jdbcType=BIGINT} "
                + "AND \"user_id\" = #{userId,jdbcType=BIGINT}"));

        MyBatisDatabaseTable withoutKey = table(
                "audit_event",
                column("message", Types.VARCHAR, false, false, false, 1, null));
        MyBatisGenerationBundle noKeyBundle = generate(withoutKey, configuration);
        String noKeyMapper = artifact(noKeyBundle, MyBatisGenerationArtifactKind.MAPPER).content();
        assertFalse(noKeyMapper.contains("PrimaryKey"));
        String noKeyXml = artifact(noKeyBundle, MyBatisGenerationArtifactKind.XML).content();
        assertFalse(noKeyXml.contains("deleteByPrimaryKey"));
        assertFalse(noKeyXml.contains("updateByPrimaryKey"));
    }

    public void testMyBatisPlusTemplateIsExplicitAndCarriesAutoKeyAndTypeHandler() {
        MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                "com.example",
                "src/main/java",
                "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                MyBatisGenerationTemplateGroup.MYBATIS_PLUS,
                "",
                "",
                false,
                true,
                Set.of(),
                Map.of("payload", new MyBatisGenerationColumnOverride(
                        Optional.empty(),
                        Optional.of("java.lang.String"),
                        Optional.of("com.example.JsonTypeHandler"))));
        MyBatisDatabaseTable table = table(
                "event",
                column("id", Types.BIGINT, false, true, true, 1, null),
                column("payload", Types.VARCHAR, true, false, false, 2, null));

        MyBatisGenerationBundle bundle = generate(table, configuration);

        String entity = artifact(bundle, MyBatisGenerationArtifactKind.ENTITY).content();
        assertTrue(entity.contains("@TableName(\"\\\"event\\\"\")"));
        assertTrue(entity.contains("@TableId(value = \"\\\"id\\\"\", type = IdType.AUTO)"));
        assertTrue(entity.contains("typeHandler = JsonTypeHandler.class"));
        String mapper = artifact(bundle, MyBatisGenerationArtifactKind.MAPPER).content();
        assertTrue(mapper.contains("extends BaseMapper<Event>"));
        assertFalse(mapper.contains("selectAll"));
        String service = artifact(bundle, MyBatisGenerationArtifactKind.SERVICE).content();
        assertTrue(service.contains("extends ServiceImpl<EventMapper, Event>"));
    }

    public void testRejectsUnsafePathsDuplicatePropertiesAndEmptySelection() {
        expectIllegalArgument(() -> new MyBatisGenerationConfiguration(
                "com.example", "../java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.STANDARD, "", "", true, true,
                Set.of(), Map.of()));
        expectIllegalArgument(() -> new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "/tmp/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.STANDARD, "", "", true, true,
                Set.of(), Map.of()));
        expectIllegalArgument(() -> new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(), MyBatisGenerationTemplateGroup.STANDARD, "", "", true, true,
                Set.of(), Map.of()));
        MyBatisGenerationConfiguration duplicate = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.STANDARD, "", "", true, true,
                Set.of(),
                Map.of(
                        "first_name", new MyBatisGenerationColumnOverride(
                                Optional.of("name"), Optional.empty(), Optional.empty()),
                        "last_name", new MyBatisGenerationColumnOverride(
                                Optional.of("name"), Optional.empty(), Optional.empty())));
        expectIllegalArgument(() -> generate(table(
                "person",
                column("first_name", Types.VARCHAR, true, false, false, 1, null),
                column("last_name", Types.VARCHAR, true, false, false, 2, null)), duplicate));
    }

    public void testRejectsDynamicTokensAndUnsafeUnescapedSqlIdentifiers() {
        MyBatisGenerationConfiguration escaped = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.MYBATIS_PLUS, "", "", true, true,
                Set.of(), Map.of());
        IllegalArgumentException tokenFailure = expectIllegalArgument(() -> generate(table(
                "users${status}",
                column("id", Types.BIGINT, false, true, false, 1, null)), escaped));
        assertTrue(tokenFailure.getMessage().contains("动态替换令牌"));

        IllegalArgumentException columnTokenFailure = expectIllegalArgument(() -> generate(table(
                "users",
                column("name#{value}", Types.VARCHAR, true, false, false, 1, null)),
                escaped));
        assertTrue(columnTokenFailure.getMessage().contains("动态替换令牌"));

        MyBatisDatabaseTable quotedTable = table(
                "order",
                column("select", Types.VARCHAR, true, false, false, 1, null));
        MyBatisGenerationBundle quotedBundle = generate(
                quotedTable, escaped, MyBatisSqlDialect.MYSQL);
        String quotedEntity = artifact(
                quotedBundle, MyBatisGenerationArtifactKind.ENTITY).content();
        assertTrue(quotedEntity.contains("@TableName(\"`order`\")"));
        assertTrue(quotedEntity.contains("@TableField(value = \"`select`\")"));

        MyBatisGenerationConfiguration unescaped = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                MyBatisGenerationTemplateGroup.STANDARD, "", "", true, false,
                Set.of(), Map.of());
        IllegalArgumentException rawFailure = expectIllegalArgument(() -> generate(table(
                "users; DELETE FROM audit",
                column("id", Types.BIGINT, false, true, false, 1, null)), unescaped));
        assertTrue(rawFailure.getMessage().contains("已关闭标识符转义"));
    }

    public void testQualifiesCatalogAndSchemaWithoutChangingSelectedTableIdentity() {
        MyBatisGenerationConfiguration configuration = MyBatisGenerationConfiguration
                .standard("com.example");
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.of("tenant_catalog"),
                Optional.of("audit_schema"),
                "users",
                java.util.List.of(column(
                        "id", Types.BIGINT, false, true, false, 1, null)));

        String mysqlXml = artifact(
                generate(table, configuration, MyBatisSqlDialect.MYSQL),
                MyBatisGenerationArtifactKind.XML).content();
        assertTrue(mysqlXml.contains("FROM `tenant_catalog`.`users`"));
        assertFalse(mysqlXml.contains("`audit_schema`.`users`"));

        String sqlServerXml = artifact(
                generate(table, configuration, MyBatisSqlDialect.SQL_SERVER),
                MyBatisGenerationArtifactKind.XML).content();
        assertTrue(sqlServerXml.contains(
                "FROM [tenant_catalog].[audit_schema].[users]"));

        String postgresXml = artifact(
                generate(table, configuration, MyBatisSqlDialect.POSTGRESQL),
                MyBatisGenerationArtifactKind.XML).content();
        assertTrue(postgresXml.contains("FROM \"audit_schema\".\"users\""));
        assertFalse(postgresXml.contains("\"tenant_catalog\".\"audit_schema\""));
    }

    public void testRejectsViewsAndKeepsGeneratedPlusColumnsReadOnly() {
        MyBatisGenerationConfiguration standard = MyBatisGenerationConfiguration
                .standard("com.example");
        MyBatisDatabaseTable view = new MyBatisDatabaseTable(
                Optional.empty(), Optional.of("public"), "user_view", Optional.empty(),
                MyBatisDatabaseObjectKind.VIEW,
                java.util.List.of(column(
                        "id", Types.BIGINT, false, true, false, 1, null)));
        IllegalArgumentException viewFailure = expectIllegalArgument(
                () -> generate(view, standard));
        assertTrue(viewFailure.getMessage().contains("物理表"));

        MyBatisGenerationConfiguration plus = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.MYBATIS_PLUS, "", "", true, true,
                Set.of(), Map.of());
        MyBatisDatabaseColumn generated = new MyBatisDatabaseColumn(
                "name_upper", "VARCHAR", Types.VARCHAR, true,
                false, false, false, true, Optional.empty(), 2);
        MyBatisDatabaseTable table = table(
                "users",
                column("id", Types.BIGINT, false, true, true, 1, null),
                generated);
        String entity = artifact(generate(table, plus),
                MyBatisGenerationArtifactKind.ENTITY).content();
        assertTrue(entity.contains("@TableId(value = \"\\\"id\\\"\", type = IdType.AUTO)"));
        assertTrue(entity.contains("import com.baomidou.mybatisplus.annotation.FieldStrategy;"));
        assertTrue(entity.contains("@TableField(value = \"\\\"name_upper\\\"\", "
                + "insertStrategy = FieldStrategy.NEVER, "
                + "updateStrategy = FieldStrategy.NEVER)"));

        MyBatisGenerationBundle standardBundle = generate(table, standard);
        String mapper = artifact(standardBundle,
                MyBatisGenerationArtifactKind.MAPPER).content();
        String xml = artifact(standardBundle,
                MyBatisGenerationArtifactKind.XML).content();
        String service = artifact(standardBundle,
                MyBatisGenerationArtifactKind.SERVICE).content();
        assertFalse(mapper.contains("updateByPrimaryKey"));
        assertFalse(xml.contains("updateByPrimaryKey"));
        assertFalse(service.contains("mapper.updateByPrimaryKey"));

        MyBatisDatabaseColumn generatedPrimaryKey = new MyBatisDatabaseColumn(
                "computed_id", "BIGINT", Types.BIGINT, false,
                true, false, false, true, Optional.empty(), 1);
        IllegalArgumentException primaryKeyFailure = expectIllegalArgument(
                () -> generate(table("computed_key", generatedPrimaryKey), plus));
        assertTrue(primaryKeyFailure.getMessage().contains("generated primary key")
                || primaryKeyFailure.getMessage().contains("生成主键"));
    }

    public void testEscapesRenderedIdentifiersBeforeEmbeddingThemInMapperXml() {
        MyBatisGenerationConfiguration configuration = MyBatisGenerationConfiguration
                .standard("com.example");
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.of("tenant<if test=\"attack\">"),
                "users",
                java.util.List.of(column(
                        "id", Types.BIGINT, false, true, false, 1, null)));

        String xml = artifact(generate(
                table, configuration, MyBatisSqlDialect.POSTGRESQL),
                MyBatisGenerationArtifactKind.XML).content();

        assertTrue(xml.contains("tenant&lt;if test=\"\"attack\"\"&gt;"));
        assertFalse(xml.contains("<if test=\"attack\">"));
    }

    public void testNeutralizesUnicodeEscapesInGeneratedJavaDoc() {
        String closeComment = "\\u" + "002a\\u" + "002f";
        String openComment = "\\u" + "002f\\u" + "002a";
        MyBatisDatabaseColumn named = new MyBatisDatabaseColumn(
                "name", "VARCHAR", Types.VARCHAR, true,
                false, false, false, Optional.of(
                        closeComment + " static { throw new Error(); } " + openComment), 2);
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(), Optional.empty(), "users",
                Optional.of(closeComment + " class Injected {} " + openComment),
                java.util.List.of(
                        column("id", Types.BIGINT, false, true, false, 1, null),
                        named));

        MyBatisGenerationBundle bundle = generate(
                table, MyBatisGenerationConfiguration.standard("com.example"));
        MyBatisGeneratedArtifact entity = artifact(
                bundle, MyBatisGenerationArtifactKind.ENTITY);

        assertFalse(entity.content().contains(closeComment));
        assertFalse(entity.content().contains(openComment));
        assertTrue(entity.content().contains("&#92;u002a&#92;u002f"));
        MyBatisGenerationPsiValidator.validate(getProject(), entity, entity.content());
    }

    public void testRejectsAmbiguousJavaTypeAndHandlerShortNames() {
        MyBatisGenerationConfiguration conflictingTypes = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.STANDARD, "", "", true, true,
                Set.of(),
                Map.of(
                        "created_on", new MyBatisGenerationColumnOverride(
                                Optional.empty(), Optional.of("java.util.Date"), Optional.empty()),
                        "updated_on", new MyBatisGenerationColumnOverride(
                                Optional.empty(), Optional.of("java.sql.Date"), Optional.empty())));
        IllegalArgumentException typeConflict = expectIllegalArgument(() -> generate(table(
                "audit",
                column("created_on", Types.DATE, false, false, false, 1, null),
                column("updated_on", Types.DATE, false, false, false, 2, null)),
                conflictingTypes));
        assertTrue(typeConflict.getMessage().contains("引用类型短名冲突"));

        MyBatisGenerationConfiguration conflictingHandler = new MyBatisGenerationConfiguration(
                "com.example", "src/main/java", "src/main/resources",
                Set.of(MyBatisGenerationArtifactKind.ENTITY),
                MyBatisGenerationTemplateGroup.MYBATIS_PLUS, "", "", true, true,
                Set.of(),
                Map.of("payload", new MyBatisGenerationColumnOverride(
                        Optional.empty(),
                        Optional.of("java.lang.String"),
                        Optional.of("com.example.String"))));
        IllegalArgumentException handlerConflict = expectIllegalArgument(() -> generate(table(
                "event",
                column("payload", Types.VARCHAR, true, false, false, 1, null)),
                conflictingHandler));
        assertTrue(handlerConflict.getMessage().contains("引用类型短名冲突"));
    }

    public void testPreservesTimezoneJdbcTypesAndUsesDialectSafeEmptyInsert() {
        MyBatisDatabaseTable temporal = table(
                "time_event",
                column("id", Types.BIGINT, false, true, false, 1, null),
                column("at_time", Types.TIME_WITH_TIMEZONE,
                        false, false, false, 2, null),
                column("at_timestamp", Types.TIMESTAMP_WITH_TIMEZONE,
                        false, false, false, 3, null));

        MyBatisGenerationBundle temporalBundle = generate(
                temporal, MyBatisGenerationConfiguration.standard("com.example"));

        String temporalEntity = artifact(
                temporalBundle, MyBatisGenerationArtifactKind.ENTITY).content();
        assertTrue(temporalEntity.contains("import java.time.OffsetTime;"));
        assertTrue(temporalEntity.contains("import java.time.OffsetDateTime;"));
        String temporalXml = artifact(
                temporalBundle, MyBatisGenerationArtifactKind.XML).content();
        assertTrue(temporalXml.contains("jdbcType=TIME_WITH_TIMEZONE"));
        assertTrue(temporalXml.contains("jdbcType=TIMESTAMP_WITH_TIMEZONE"));

        MyBatisDatabaseTable identityOnly = table(
                "identity_only",
                column("id", Types.BIGINT, false, true, true, 1, null));
        String mysql = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.MYSQL), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(mysql.contains("INSERT INTO `identity_only` () VALUES ()"));
        String oracle = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.ORACLE), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(oracle.contains("INSERT INTO \"identity_only\" (\"id\") VALUES (DEFAULT)"));
        String postgres = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.POSTGRESQL), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(postgres.contains("INSERT INTO \"identity_only\" DEFAULT VALUES"));
        String dameng = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.DAMENG), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(dameng.contains("INSERT INTO \"identity_only\" (\"id\") VALUES (DEFAULT)"));
    }

    private static MyBatisGenerationBundle generate(
            MyBatisDatabaseTable table,
            MyBatisGenerationConfiguration configuration) {
        return generate(table, configuration, MyBatisSqlDialect.GENERIC);
    }

    private static MyBatisGenerationBundle generate(
            MyBatisDatabaseTable table,
            MyBatisGenerationConfiguration configuration,
            MyBatisSqlDialect dialect) {
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "main", dialect, table, configuration));
    }

    private static MyBatisGeneratedArtifact artifact(
            MyBatisGenerationBundle bundle,
            MyBatisGenerationArtifactKind kind) {
        return bundle.artifacts().stream()
                .filter(artifact -> artifact.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static MyBatisDatabaseTable table(
            String name,
            MyBatisDatabaseColumn... columns) {
        return new MyBatisDatabaseTable(
                Optional.empty(), Optional.empty(), name, java.util.List.of(columns));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean autoIncrement,
            int position,
            String comment) {
        return new MyBatisDatabaseColumn(
                name,
                jdbcTypeName(jdbcType),
                jdbcType,
                nullable,
                primaryKey,
                false,
                autoIncrement,
                Optional.ofNullable(comment),
                position);
    }

    private static String jdbcTypeName(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT -> "BIGINT";
            case Types.VARCHAR -> "VARCHAR";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default -> "OTHER";
        };
    }

    private static IllegalArgumentException expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("无效生成输入必须被拒绝");
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
            // 安全生成边界不做自动纠错。
            return expected;
        }
    }

    private static void assertWellFormedXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
    }
}
