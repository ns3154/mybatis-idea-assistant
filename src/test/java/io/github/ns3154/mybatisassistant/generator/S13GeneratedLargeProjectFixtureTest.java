package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolScanner;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用真实临时文件验证大型多模块夹具的生成、指纹、安全合并与 XML 扫描闭环。
 */
public final class S13GeneratedLargeProjectFixtureTest extends BasePlatformTestCase {
    private static final Pattern CREATE_TABLE = Pattern.compile("(?m)^CREATE TABLE ");
    private static final String EXPECTED_FINGERPRINT =
            "7c36a9b984538fa48e220c01897028f76fef3c5179a0b36f8621b4a7790b74e1";
    private Path projectRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        projectRoot = Files.createTempDirectory("mybatis-assistant-large-project-");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (projectRoot != null) {
                FileUtil.delete(projectRoot.toFile());
            }
        } finally {
            super.tearDown();
        }
    }

    public void testGeneratesMergesAndScansLargeProjectDeterministically() throws Exception {
        S13LargeProjectFixtureGenerator.Manifest manifest =
                S13LargeProjectFixtureGenerator.generate(projectRoot);

        assertEquals(1_024, manifest.tableCount());
        assertEquals(5_120, manifest.statementCount());
        assertEquals(EXPECTED_FINGERPRINT, manifest.fingerprint());
        assertEquals(1_024, manifest.mapperFiles().size());
        assertEquals(20, manifest.schemaFiles().size());
        assertEquals(1_024, countCreateTables(manifest.schemaFiles()));

        long statementCount = 0L;
        long manualFragmentCount = 0L;
        for (int tableIndex = 0; tableIndex < manifest.mapperFiles().size(); tableIndex++) {
            Path mapperFile = manifest.mapperFiles().get(tableIndex);
            String existing = Files.readString(mapperFile, StandardCharsets.UTF_8);
            MyBatisSafeMergeResult.Ready merged = assertInstanceOf(
                    MyBatisSafeMerger.merge(
                            existing,
                            S13LargeProjectFixtureGenerator.desiredMapperXml(tableIndex)),
                    MyBatisSafeMergeResult.Ready.class);
            assertTrue(merged.changed());
            assertTrue(merged.text().contains("<sql id=\"manualColumns\">"));
            Files.writeString(mapperFile, merged.text(), StandardCharsets.UTF_8);

            var symbols = MyBatisXmlSymbolScanner.scan(merged.text());
            assertEquals(7, symbols.size());
            statementCount += symbols.stream()
                    .filter(symbol -> symbol.kind() == MyBatisXmlSymbolKind.STATEMENT)
                    .count();
            manualFragmentCount += symbols.stream()
                    .filter(symbol -> symbol.kind() == MyBatisXmlSymbolKind.SQL_FRAGMENT)
                    .count();

            MyBatisSafeMergeResult.Ready unchanged = assertInstanceOf(
                    MyBatisSafeMerger.merge(
                            merged.text(),
                            S13LargeProjectFixtureGenerator.desiredMapperXml(tableIndex)),
                    MyBatisSafeMergeResult.Ready.class);
            assertFalse(unchanged.changed());
        }

        assertEquals(5_120L, statementCount);
        assertEquals(1_024L, manualFragmentCount);
    }

    private static long countCreateTables(List<Path> schemaFiles) throws IOException {
        long count = 0L;
        for (Path schemaFile : schemaFiles) {
            String schema = Files.readString(schemaFile, StandardCharsets.UTF_8);
            count += CREATE_TABLE.matcher(schema).results().count();
        }
        return count;
    }
}
