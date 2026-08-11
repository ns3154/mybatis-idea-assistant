package io.github.ns3154.mybatisassistant.generator;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

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
        assertTrue(xml.contains("FROM public.t_order"));
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
        assertTrue(xml.contains("tenant_id = #{tenantId,jdbcType=BIGINT} AND user_id = #{userId,jdbcType=BIGINT}"));

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
        assertTrue(entity.contains("@TableName(\"event\")"));
        assertTrue(entity.contains("@TableId(value = \"id\", type = IdType.AUTO)"));
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
        assertTrue(mysql.contains("INSERT INTO identity_only () VALUES ()"));
        String oracle = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.ORACLE), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(oracle.contains("INSERT INTO identity_only (id) VALUES (DEFAULT)"));
        String postgres = artifact(generate(
                identityOnly,
                MyBatisGenerationConfiguration.standard("com.example"),
                MyBatisSqlDialect.POSTGRESQL), MyBatisGenerationArtifactKind.XML).content();
        assertTrue(postgres.contains("INSERT INTO identity_only DEFAULT VALUES"));
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
}
