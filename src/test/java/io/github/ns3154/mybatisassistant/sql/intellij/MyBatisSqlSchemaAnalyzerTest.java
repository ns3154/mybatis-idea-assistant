package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class MyBatisSqlSchemaAnalyzerTest extends BasePlatformTestCase {
    public void testResolvesSchemaTableAliasAndColumns() {
        MyBatisSqlSchemaAnalysis analysis = analyze(
                "select u.id, u.name from public.users u where u.id = #{id}",
                List.of(snapshot(
                        "main",
                        MyBatisMetadataFreshness.READY,
                        table("public", "users", "id", "name"))));

        assertTrue(analysis.metadataComplete());
        assertOccurrence(analysis, MyBatisSqlSymbolKind.TABLE, "users",
                MyBatisSqlSymbolStatus.RESOLVED);
        assertEquals(3, analysis.occurrences().stream()
                .filter(occurrence -> occurrence.kind() == MyBatisSqlSymbolKind.COLUMN)
                .filter(occurrence -> occurrence.status() == MyBatisSqlSymbolStatus.RESOLVED)
                .count());
    }

    public void testMissingTableSuppressesCascadingColumnErrors() {
        MyBatisSqlSchemaAnalysis analysis = analyze(
                "select u.id from missing_users u",
                List.of(snapshot(
                        "main",
                        MyBatisMetadataFreshness.READY,
                        table("public", "users", "id"))));

        assertOccurrence(analysis, MyBatisSqlSymbolKind.TABLE, "missing_users",
                MyBatisSqlSymbolStatus.MISSING);
        assertOccurrence(analysis, MyBatisSqlSymbolKind.COLUMN, "id",
                MyBatisSqlSymbolStatus.UNKNOWN);
    }

    public void testReportsMissingAndAmbiguousColumnsOnlyWithResolvedTables() {
        MyBatisSqlSchemaAnalysis missing = analyze(
                "select u.missing from users u",
                List.of(snapshot(
                        "main",
                        MyBatisMetadataFreshness.READY,
                        table("public", "users", "id"))));
        assertOccurrence(missing, MyBatisSqlSymbolKind.COLUMN, "missing",
                MyBatisSqlSymbolStatus.MISSING);

        MyBatisSqlSchemaAnalysis ambiguous = analyze(
                "select id from users u join orders o on u.id = o.user_id",
                List.of(snapshot(
                        "main",
                        MyBatisMetadataFreshness.READY,
                        table("public", "users", "id"),
                        table("public", "orders", "id", "user_id"))));
        assertOccurrence(ambiguous, MyBatisSqlSymbolKind.COLUMN, "id",
                MyBatisSqlSymbolStatus.AMBIGUOUS);
        assertOccurrence(ambiguous, MyBatisSqlSymbolKind.COLUMN, "user_id",
                MyBatisSqlSymbolStatus.RESOLVED);
    }

    public void testDuplicateTablesAndLoadingMetadataStayConservative() {
        MyBatisDatabaseTable users = table("public", "users", "id");
        MyBatisSqlSchemaAnalysis duplicate = analyze(
                "select id from users",
                List.of(
                        snapshot("first", MyBatisMetadataFreshness.READY, users),
                        snapshot("second", MyBatisMetadataFreshness.READY, users)));
        assertOccurrence(duplicate, MyBatisSqlSymbolKind.TABLE, "users",
                MyBatisSqlSymbolStatus.AMBIGUOUS);
        assertOccurrence(duplicate, MyBatisSqlSymbolKind.COLUMN, "id",
                MyBatisSqlSymbolStatus.UNKNOWN);

        MyBatisSqlSchemaAnalysis loading = analyze(
                "select missing from users",
                List.of(snapshot("main", MyBatisMetadataFreshness.LOADING, users)));
        assertFalse(loading.metadataComplete());
        assertEmpty(loading.occurrences());

        MyBatisSqlSchemaAnalysis mixed = analyze(
                "select * from missing_users",
                List.of(
                        snapshot("ready", MyBatisMetadataFreshness.READY, users),
                        snapshot("loading", MyBatisMetadataFreshness.LOADING)));
        assertFalse(mixed.metadataComplete());
        assertEmpty(mixed.occurrences());
    }

    public void testDynamicTableIdentifierIsNotReportedMissing() {
        MyBatisSqlSchemaAnalysis analysis = analyze(
                "select id from ${table}",
                List.of(snapshot(
                        "main",
                        MyBatisMetadataFreshness.READY,
                        table("public", "users", "id"))));

        assertFalse(analysis.occurrences().stream()
                .anyMatch(occurrence -> occurrence.kind() == MyBatisSqlSymbolKind.TABLE));
    }

    public void testCancellationPropagatesBeforeMetadataTraversal() {
        MyBatisSqlPsiResult.Ready ready = ready("select id from users");
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return MyBatisSqlSchemaAnalyzer.analyze(
                                ready,
                                List.of(snapshot(
                                        "main",
                                        MyBatisMetadataFreshness.READY,
                                        table("public", "users", "id"))));
                    },
                    indicator);
            fail("取消后的 SQL schema 分析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转成 UNKNOWN。
        }
    }

    public void testThousandTableHotAnalysisP95StaysWithinInteractiveBudget() {
        List<MyBatisDatabaseTable> tables = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            tables.add(table("public", "table_" + index, "id", "name"));
        }
        MyBatisSqlPsiResult.Ready ready = ready("select id from table_999");
        List<MyBatisDatabaseSnapshot> snapshots = List.of(snapshot(
                "main",
                MyBatisMetadataFreshness.READY,
                tables.toArray(MyBatisDatabaseTable[]::new)));
        for (int warmup = 0; warmup < 5; warmup++) {
            assertOccurrence(
                    MyBatisSqlSchemaAnalyzer.analyze(ready, snapshots),
                    MyBatisSqlSymbolKind.TABLE,
                    "table_999",
                    MyBatisSqlSymbolStatus.RESOLVED);
        }
        List<Long> durations = new ArrayList<>();
        for (int sample = 0; sample < 100; sample++) {
            long started = System.nanoTime();
            MyBatisSqlSchemaAnalysis analysis = MyBatisSqlSchemaAnalyzer.analyze(
                    ready,
                    snapshots);
            durations.add(System.nanoTime() - started);
            assertOccurrence(analysis, MyBatisSqlSymbolKind.COLUMN, "id",
                    MyBatisSqlSymbolStatus.RESOLVED);
        }

        Collections.sort(durations);
        long p95Nanos = durations.get((int) Math.ceil(durations.size() * 0.95) - 1);
        assertTrue("千表热分析 P95 超过 150ms，实际=" + p95Nanos / 1_000_000
                        + "ms，样本=" + durations,
                p95Nanos < 150_000_000L);
    }

    private MyBatisSqlSchemaAnalysis analyze(
            String sql,
            List<MyBatisDatabaseSnapshot> snapshots) {
        return MyBatisSqlSchemaAnalyzer.analyze(ready(sql), snapshots);
    }

    private MyBatisSqlPsiResult.Ready ready(String sql) {
        XmlFile file = (XmlFile) myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">%s</select>
                </mapper>
                """.formatted(sql));
        XmlTag root = file.getRootTag();
        assertNotNull(root);
        XmlTag statement = root.findFirstSubTag("select");
        assertNotNull(statement);
        return assertInstanceOf(
                MyBatisSqlPsiService.getInstance(getProject()).parse(statement),
                MyBatisSqlPsiResult.Ready.class);
    }

    private static void assertOccurrence(
            MyBatisSqlSchemaAnalysis analysis,
            MyBatisSqlSymbolKind kind,
            String name,
            MyBatisSqlSymbolStatus status) {
        assertTrue(analysis.occurrences().toString(), analysis.occurrences().stream()
                .anyMatch(occurrence -> occurrence.kind() == kind
                        && name.equals(occurrence.name())
                        && occurrence.status() == status));
    }

    private static MyBatisDatabaseSnapshot snapshot(
            String id,
            MyBatisMetadataFreshness freshness,
            MyBatisDatabaseTable... tables) {
        return new MyBatisDatabaseSnapshot(
                id,
                id,
                MyBatisSqlDialect.GENERIC,
                freshness,
                1,
                List.of(tables));
    }

    private static MyBatisDatabaseTable table(
            String schema,
            String name,
            String... columns) {
        List<MyBatisDatabaseColumn> metadata = java.util.stream.IntStream
                .range(0, columns.length)
                .mapToObj(index -> new MyBatisDatabaseColumn(
                        columns[index],
                        "VARCHAR",
                        Types.VARCHAR,
                        true,
                        false,
                        false,
                        index + 1))
                .toList();
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.of(schema),
                name,
                metadata);
    }
}
