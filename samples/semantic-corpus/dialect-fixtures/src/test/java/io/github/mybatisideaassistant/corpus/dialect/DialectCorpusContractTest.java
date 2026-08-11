package io.github.mybatisideaassistant.corpus.dialect;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DialectCorpusContractTest {
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
}
