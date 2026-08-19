package io.github.ns3154.mybatisassistant.index;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbol;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;

import java.util.Arrays;
import java.util.Set;

public final class S13LargeProjectPerformanceTest extends BasePlatformTestCase {
    private static final int MAPPER_COUNT = 2_000;
    private static final int STATEMENTS_PER_MAPPER = 5;
    private static final int LINES_PER_MAPPER = 500;
    private static final int MODULE_COUNT = 4;
    private static final int DATA_SOURCE_COUNT = 4;

    public void testScansMillionLineFixtureAcrossModuleAndDataSourceDimensionsWithinBudget() {
        long[] durations = new long[MAPPER_COUNT];
        long totalLines = 0L;
        long totalStatements = 0L;

        for (int mapperIndex = 0; mapperIndex < MAPPER_COUNT; mapperIndex++) {
            String namespace = "fixture.module"
                    + mapperIndex % MODULE_COUNT
                    + ".datasource"
                    + mapperIndex % DATA_SOURCE_COUNT
                    + ".Mapper"
                    + mapperIndex;
            String mapperXml = mapperXml(namespace);
            totalLines += mapperXml.lines().count();

            long started = System.nanoTime();
            Set<MyBatisXmlSymbol> symbols = MyBatisXmlSymbolScanner.scan(mapperXml);
            durations[mapperIndex] = System.nanoTime() - started;

            assertEquals(STATEMENTS_PER_MAPPER + 1, symbols.size());
            assertTrue(symbols.contains(MyBatisXmlSymbol.namespace(namespace)));
            for (int statementIndex = 0;
                    statementIndex < STATEMENTS_PER_MAPPER;
                    statementIndex++) {
                assertTrue(symbols.contains(MyBatisXmlSymbol.named(
                        MyBatisXmlSymbolKind.STATEMENT,
                        namespace,
                        "statement" + statementIndex)));
                totalStatements++;
            }
        }

        assertEquals(1_000_000L, totalLines);
        assertEquals(10_000L, totalStatements);
        assertP95Below(durations, 50.0);
    }

    private static String mapperXml(String namespace) {
        StringBuilder xml = new StringBuilder(LINES_PER_MAPPER * 32);
        xml.append("<mapper namespace=\"").append(namespace).append("\">\n");
        for (int statementIndex = 0;
                statementIndex < STATEMENTS_PER_MAPPER;
                statementIndex++) {
            xml.append("  <select id=\"statement")
                    .append(statementIndex)
                    .append("\">select ")
                    .append(statementIndex)
                    .append("</select>\n");
        }
        int paddingLines = LINES_PER_MAPPER - STATEMENTS_PER_MAPPER - 2;
        for (int line = 0; line < paddingLines; line++) {
            xml.append("  <!-- 性能夹具填充行 ").append(line).append(" -->\n");
        }
        xml.append("</mapper>");
        return xml.toString();
    }

    private static void assertP95Below(long[] durations, double thresholdMillis) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        int percentileIndex = (int) Math.ceil(sorted.length * 0.95) - 1;
        double p95Millis = sorted[percentileIndex] / 1_000_000.0;
        assertTrue(
                "百万行夹具单 Mapper 扫描 P95 为 "
                        + p95Millis
                        + "ms，阈值为 "
                        + thresholdMillis
                        + "ms",
                p95Millis < thresholdMillis);
    }
}
