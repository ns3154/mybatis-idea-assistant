package io.github.ns3154.mybatisassistant.generator;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MyBatisGenerationGoldenTest extends BasePlatformTestCase {
    private static final String EXPECTED_SHA256 =
            "21872fd4dea5457f3bfd2376d4fac5964bd6cb3de720003b23f817951efa97b5";

    public void testOneHundredSchemaEvolutionGoldenCasesPreserveManualContent() throws Exception {
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < 100; index++) {
            MyBatisGenerationConfiguration configuration = configuration(index);
            MyBatisGenerationBundle first = generate(index, configuration, false);
            MyBatisGenerationBundle changed = generate(index, configuration, true);
            assertEquals(first.entityName(), changed.entityName());
            assertEquals(first.artifacts().stream().map(MyBatisGeneratedArtifact::relativePath)
                            .toList(),
                    changed.artifacts().stream().map(MyBatisGeneratedArtifact::relativePath)
                            .toList());
            for (int artifactIndex = 0;
                    artifactIndex < first.artifacts().size();
                    artifactIndex++) {
                MyBatisGeneratedArtifact before = first.artifacts().get(artifactIndex);
                MyBatisGeneratedArtifact desired = changed.artifacts().get(artifactIndex);
                String manualToken = "S8-MANUAL-" + index + "-" + before.kind();
                String existing = addManualCode(before, manualToken);
                if ((index & 1) == 0) {
                    existing = existing.replace("\n", "\r\n");
                }

                MyBatisSafeMergeResult.Ready merged = assertInstanceOf(
                        MyBatisSafeMerger.merge(existing, desired.content()),
                        MyBatisSafeMergeResult.Ready.class);

                assertTrue("手写区丢失：" + index + "/" + before.kind(),
                        merged.text().contains(manualToken));
                if ((index & 1) == 0) {
                    assertTrue(merged.text().contains("\r\n"));
                    assertFalse(merged.text().replace("\r\n", "").contains("\n"));
                }
                MyBatisSafeMergeResult.Ready repeated = assertInstanceOf(
                        MyBatisSafeMerger.merge(merged.text(), desired.content()),
                        MyBatisSafeMergeResult.Ready.class);
                assertFalse("重复生成必须幂等：" + index + "/" + before.kind(),
                        repeated.changed());
                assertEquals(merged.text(), repeated.text());
                aggregate.update((index + "|" + desired.relativePath() + "|")
                        .getBytes(StandardCharsets.UTF_8));
                aggregate.update(merged.text().getBytes(StandardCharsets.UTF_8));
            }
            MyBatisGenerationBundle deterministic = generate(
                    index, configuration, true);
            assertEquals(changed.artifacts(), deterministic.artifacts());
        }
        assertEquals(EXPECTED_SHA256,
                HexFormat.of().formatHex(aggregate.digest()));
    }

    private static MyBatisGenerationConfiguration configuration(int index) {
        MyBatisGenerationTemplateGroup template = index % 5 == 0
                ? MyBatisGenerationTemplateGroup.MYBATIS_PLUS
                : MyBatisGenerationTemplateGroup.STANDARD;
        Map<String, MyBatisGenerationColumnOverride> overrides = index % 3 == 0
                ? Map.of("payload", new MyBatisGenerationColumnOverride(
                        Optional.of("content"),
                        Optional.of("java.lang.String"),
                        Optional.of("com.example.JsonTypeHandler")))
                : Map.of();
        return new MyBatisGenerationConfiguration(
                "com.example.golden.case" + index,
                "src/main/java",
                "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                template,
                index % 2 == 0 ? "t_" : "",
                index % 9 == 0 ? "Entity" : "",
                true,
                true,
                index % 11 == 0 ? Set.of("ignored_column") : Set.of(),
                overrides);
    }

    private static MyBatisGenerationBundle generate(
            int index,
            MyBatisGenerationConfiguration configuration,
            boolean changed) {
        List<MyBatisDatabaseColumn> columns = new ArrayList<>();
        boolean noPrimaryKey = index % 7 == 0;
        boolean compositeKey = !noPrimaryKey && index % 10 == 0;
        if (compositeKey) {
            columns.add(column("tenant_id", Types.BIGINT, false, true, false, 1));
        }
        columns.add(column(
                "id",
                Types.BIGINT,
                false,
                !noPrimaryKey,
                !noPrimaryKey && !compositeKey && index % 2 == 0,
                columns.size() + 1));
        int mutation = index % 4;
        if (!changed || mutation == 0) {
            columns.add(column("name", Types.VARCHAR, true, false, false,
                    columns.size() + 1));
        } else if (mutation == 2) {
            columns.add(column("display_name", Types.VARCHAR, false, false, false,
                    columns.size() + 1));
        } else if (mutation == 3) {
            columns.add(column("name", Types.BIGINT, true, false, false,
                    columns.size() + 1));
        }
        if (changed && mutation == 0) {
            columns.add(column("email", Types.VARCHAR, true, false, false,
                    columns.size() + 1));
        }
        if (index % 3 == 0) {
            columns.add(column("payload", Types.VARCHAR, true, false, false,
                    columns.size() + 1));
        }
        columns.add(column("ignored_column", Types.TIMESTAMP, true, false, false,
                columns.size() + 1));
        String tableName = (index % 2 == 0 ? "t_" : "") + "order_case_" + index;
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.of("catalog" + index % 2),
                Optional.of("schema" + index % 3),
                tableName,
                Optional.of("黄金样例表 " + index),
                columns);
        MyBatisSqlDialect[] dialects = MyBatisSqlDialect.values();
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "golden-" + index,
                dialects[index % dialects.length],
                table,
                configuration));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean nullable,
            boolean primaryKey,
            boolean autoIncrement,
            int position) {
        String typeName = switch (jdbcType) {
            case Types.BIGINT -> "BIGINT";
            case Types.TIMESTAMP -> "TIMESTAMP";
            default -> "VARCHAR";
        };
        return new MyBatisDatabaseColumn(
                name,
                typeName,
                jdbcType,
                nullable,
                primaryKey,
                false,
                autoIncrement,
                Optional.of("字段 " + name),
                position);
    }

    private static String addManualCode(
            MyBatisGeneratedArtifact artifact,
            String manualToken) {
        if (artifact.kind() == MyBatisGenerationArtifactKind.XML) {
            return artifact.content().replace(
                    "\n</mapper>\n",
                    "\n    <!-- " + manualToken + " -->\n</mapper>\n");
        }
        return artifact.content().replace(
                "\n}\n",
                "\n    // " + manualToken + "\n}\n");
    }
}
