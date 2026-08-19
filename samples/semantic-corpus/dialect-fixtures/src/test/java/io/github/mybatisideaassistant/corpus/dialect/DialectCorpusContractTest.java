package io.github.mybatisideaassistant.corpus.dialect;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DialectCorpusContractTest {
    private static final Set<String> VERSIONED_DATABASES = Set.of(
            "mysql",
            "postgresql",
            "oracle",
            "sqlserver",
            "sqlite"
    );
    private static final Set<String> P0_CAPABILITIES = Set.of(
            "P0-RECOGNITION",
            "P0-NAVIGATION",
            "P0-REFERENCES",
            "P0-INSPECTIONS",
            "P0-QUICKFIX",
            "P0-PARAMETERS",
            "P0-RESULTMAP",
            "P0-DYNAMICSQL",
            "P0-OGNL",
            "P0-SQL",
            "P0-DATABASE-GENERATION",
            "P0-SAFE-MERGE",
            "P0-PRODUCTIZATION"
    );

    @Test
    public void containsDistinctFixturesForEveryPromisedDatabase() throws IOException {
        Map<String, String> requiredTokens = Map.of(
                "mysql", "auto_increment",
                "postgresql", "jsonb",
                "oracle", "varchar2",
                "sqlserver", "nvarchar",
                "sqlite", "autoincrement",
                "dameng", "identity"
        );

        for (var entry : requiredTokens.entrySet()) {
            String path = "/sql/" + entry.getKey() + ".sql";
            try (var input = getClass().getResourceAsStream(path)) {
                assertNotNull("缺少方言夹具：" + path, input);
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                assertTrue(path + " 缺少方言标识", sql.contains(entry.getValue()));
                assertTrue(path + " 缺少建表语句", sql.contains("create table"));
                assertTrue(path + " 缺少查询语句", sql.contains("select"));
            }
        }
    }

    @Test
    public void pinsAndValidatesPromisedDatabaseFixtureVersions() throws IOException {
        Map<String, VersionedFixture> fixtures = new LinkedHashMap<>();
        try (var input = getClass().getResourceAsStream("/sql/versions/manifest.tsv")) {
            assertNotNull("缺少版本化方言夹具清单", input);
            String manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : manifest.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                assertTrue("版本化方言清单必须包含四列：" + line, columns.length == 4);
                VersionedFixture previous = fixtures.put(
                        columns[0],
                        new VersionedFixture(columns[1], columns[2], columns[3]));
                assertTrue("数据库版本声明重复：" + columns[0], previous == null);
            }
        }

        assertTrue("版本化数据库集合不完整", fixtures.keySet().equals(VERSIONED_DATABASES));
        for (var entry : fixtures.entrySet()) {
            VersionedFixture fixture = entry.getValue();
            assertTrue("数据库版本必须精确固定：" + fixture.version(),
                    fixture.version().matches("(?:\\d+\\.){1,2}\\d+|\\d{2}c|\\d{4}"));
            try (var input = getClass().getResourceAsStream("/" + fixture.resource())) {
                assertNotNull("缺少版本化方言夹具：" + fixture.resource(), input);
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                assertTrue("夹具数据库声明不匹配：" + fixture.resource(),
                        sql.contains("fixture-database: " + entry.getKey()));
                assertTrue("夹具版本声明不匹配：" + fixture.resource(),
                        sql.contains("fixture-version: " + fixture.version().toLowerCase()));
                assertTrue("夹具缺少版本特征：" + fixture.resource(),
                        sql.contains(fixture.featureToken()));
                assertTrue("夹具缺少建表语句：" + fixture.resource(), sql.contains("create table"));
                assertTrue("夹具缺少查询语句：" + fixture.resource(), sql.contains("select"));
            }
        }
    }

    @Test
    public void everyP0CapabilityHasNormalAndFailureCorpus() throws IOException {
        Map<String, Set<String>> kindsByCapability = new HashMap<>();
        try (var input = getClass().getResourceAsStream("/corpus/behavior-cases.tsv")) {
            assertNotNull("缺少 P0 行为语料清单", input);
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                assertTrue("行为语料必须包含四列：" + line, columns.length == 4);
                kindsByCapability.computeIfAbsent(columns[0], ignored -> new HashSet<>()).add(columns[1]);
            }
        }

        assertTrue("出现未登记的 P0 编号", P0_CAPABILITIES.containsAll(kindsByCapability.keySet()));
        for (String capability : P0_CAPABILITIES) {
            assertTrue(capability + " 缺少 NORMAL 语料",
                    kindsByCapability.getOrDefault(capability, Set.of()).contains("NORMAL"));
            assertTrue(capability + " 缺少 FAILURE 语料",
                    kindsByCapability.getOrDefault(capability, Set.of()).contains("FAILURE"));
        }
    }

    private record VersionedFixture(String version, String resource, String featureToken) {
    }
}
