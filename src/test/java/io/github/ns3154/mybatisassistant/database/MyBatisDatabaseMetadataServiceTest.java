package io.github.ns3154.mybatisassistant.database;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class MyBatisDatabaseMetadataServiceTest extends BasePlatformTestCase {
    public void testCallsProviderOffEdtAndFiltersDataSourceAndSchema() throws Exception {
        AtomicBoolean calledOnEdt = new AtomicBoolean(true);
        register(new MyBatisDatabaseMetadataProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public List<MyBatisDatabaseSnapshot> load(
                    Project project,
                    MyBatisDatabaseRequest request,
                    ProgressIndicator indicator) {
                calledOnEdt.set(ApplicationManager.getApplication().isDispatchThread());
                return List.of(
                        snapshot("first", "First", "public", "users"),
                        snapshot("second", "Second", "audit", "events"),
                        snapshot("second", "Second Other", "public", "users"));
            }
        });

        MyBatisDatabaseMetadataResult result = service().load(
                new MyBatisDatabaseRequest(Optional.of("second"), Optional.of("audit")),
                Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);

        MyBatisDatabaseMetadataResult.Loaded loaded = assertInstanceOf(
                result,
                MyBatisDatabaseMetadataResult.Loaded.class);
        assertFalse(calledOnEdt.get());
        assertEquals(2, loaded.snapshots().size());
        assertEquals("Second", loaded.snapshots().getFirst().displayName());
        assertEquals(1, loaded.snapshots().getFirst().tables().size());
        assertEquals("events", loaded.snapshots().getFirst().tables().getFirst().name());
        assertEmpty(loaded.snapshots().get(1).tables());
    }

    public void testEmptyDatabaseToolsProjectAndProviderFailureAreTyped() throws Exception {
        assertTrue(MyBatisDatabaseMetadataProvider.EP_NAME.getExtensionList().stream()
                .anyMatch(provider -> "jetbrains-database-tools".equals(provider.id())));
        MyBatisDatabaseMetadataResult noDataSource = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
        MyBatisDatabaseMetadataResult.Unavailable unavailable = assertInstanceOf(
                noDataSource,
                MyBatisDatabaseMetadataResult.Unavailable.class);
        assertEquals(MyBatisDatabaseMetadataResult.Reason.NO_DATA_SOURCE, unavailable.reason());

        register(new MyBatisDatabaseMetadataProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<MyBatisDatabaseSnapshot> load(
                    Project project,
                    MyBatisDatabaseRequest request,
                    ProgressIndicator indicator) {
                throw new IllegalStateException("测试提供方失败");
            }
        });
        MyBatisDatabaseMetadataResult failed = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
        assertEquals(
                List.of("broken"),
                assertInstanceOf(failed, MyBatisDatabaseMetadataResult.Failed.class).providerIds());
    }

    public void testTimeoutCancelsIndicatorAndReturnsTypedResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        register(blockingProvider(started, cancelled));

        MyBatisDatabaseMetadataResult result = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofMillis(40)).get(2, TimeUnit.SECONDS);

        assertEquals(0, started.getCount());
        MyBatisDatabaseMetadataResult.Unavailable unavailable = assertInstanceOf(
                result,
                MyBatisDatabaseMetadataResult.Unavailable.class);
        assertEquals(MyBatisDatabaseMetadataResult.Reason.TIMED_OUT, unavailable.reason());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
    }

    public void testTimedOutProviderCannotWriteLateSnapshot() throws Exception {
        service().invalidate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        register(new MyBatisDatabaseMetadataProvider() {
            @Override
            public String id() {
                return "ignores-cancellation";
            }

            @Override
            public List<MyBatisDatabaseSnapshot> load(
                    Project project,
                    MyBatisDatabaseRequest request,
                    ProgressIndicator indicator) {
                started.countDown();
                while (!indicator.isCanceled()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                }
                providerReturned.countDown();
                return List.of(snapshot("late", "Late", "public", "users"));
            }
        });

        MyBatisDatabaseMetadataResult result = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofMillis(40)).get(2, TimeUnit.SECONDS);

        assertEquals(0, started.getCount());
        assertEquals(
                MyBatisDatabaseMetadataResult.Reason.TIMED_OUT,
                assertInstanceOf(
                        result,
                        MyBatisDatabaseMetadataResult.Unavailable.class).reason());
        assertTrue(providerReturned.await(1, TimeUnit.SECONDS));
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        assertTrue(service().latest().isEmpty());
    }

    public void testCallerCancellationPropagatesToProviderIndicator() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        register(blockingProvider(started, cancelled));
        CompletableFuture<MyBatisDatabaseMetadataResult> future = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(2));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(future.cancel(false));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
    }

    public void testRefreshSharesInFlightWorkAndInvalidationCancelsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        register(blockingProvider(started, cancelled));

        CompletableFuture<MyBatisDatabaseMetadataResult> first = service().refresh();
        CompletableFuture<MyBatisDatabaseMetadataResult> second = service().refresh();

        assertSame(first, second);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        service().invalidate();
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(first.isCancelled());
        assertTrue(service().latest().isEmpty());
    }

    public void testDialectMappingIsStableAndConservative() {
        assertEquals(MyBatisSqlDialect.MYSQL, MyBatisSqlDialect.fromDatabaseId("maria-db"));
        assertEquals(MyBatisSqlDialect.POSTGRESQL, MyBatisSqlDialect.fromDatabaseId("PostgreSQL"));
        assertEquals(MyBatisSqlDialect.ORACLE, MyBatisSqlDialect.fromDatabaseId("oracle"));
        assertEquals(MyBatisSqlDialect.SQL_SERVER, MyBatisSqlDialect.fromDatabaseId("sql_server"));
        assertEquals(MyBatisSqlDialect.SQLITE, MyBatisSqlDialect.fromDatabaseId("sqlite"));
        assertEquals(MyBatisSqlDialect.H2, MyBatisSqlDialect.fromDatabaseId("h2"));
        assertEquals(MyBatisSqlDialect.GENERIC, MyBatisSqlDialect.fromDatabaseId("custom"));
        assertEquals(MyBatisSqlDialect.GENERIC, MyBatisSqlDialect.fromDatabaseId(null));
    }

    public void testInvalidationClearsSnapshotAndRejectsLateBackgroundWriteBack()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        register(new MyBatisDatabaseMetadataProvider() {
            @Override
            public String id() {
                return "late-result";
            }

            @Override
            public List<MyBatisDatabaseSnapshot> load(
                    Project project,
                    MyBatisDatabaseRequest request,
                    ProgressIndicator indicator) {
                started.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试未释放后台提供方");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                return List.of(snapshot("main", "Main", "public", "users"));
            }
        });
        CompletableFuture<MyBatisDatabaseMetadataResult> loading = service().load(
                MyBatisDatabaseRequest.all(),
                Duration.ofSeconds(2));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        service().invalidate();
        release.countDown();

        assertInstanceOf(loading.get(2, TimeUnit.SECONDS),
                MyBatisDatabaseMetadataResult.Loaded.class);
        assertTrue(service().latest().isEmpty());
    }

    public void testMetadataRecordsValidateAndDefensivelyCopyInputs() {
        expectIllegalArgument(() -> new MyBatisDatabaseColumn(
                "", "BIGINT", java.sql.Types.BIGINT, false, false, false, 1));
        expectIllegalArgument(() -> new MyBatisDatabaseColumn(
                "id", "BIGINT", java.sql.Types.BIGINT, false, false, false, -1));
        MyBatisDatabaseColumn unknownType = new MyBatisDatabaseColumn(
                "id", "", java.sql.Types.OTHER, true, false, false, 0);
        assertEquals("UNKNOWN", unknownType.typeName());

        expectIllegalArgument(() -> new MyBatisDatabaseTable(
                Optional.empty(), Optional.empty(), "", List.of()));
        List<MyBatisDatabaseColumn> mutableColumns = new ArrayList<>();
        MyBatisDatabaseTable table = new MyBatisDatabaseTable(
                Optional.empty(), Optional.of("public"), "users", mutableColumns);
        mutableColumns.add(unknownType);
        assertEmpty(table.columns());

        expectIllegalArgument(() -> new MyBatisDatabaseSnapshot(
                "", "Main", MyBatisSqlDialect.GENERIC,
                MyBatisMetadataFreshness.READY, 0, List.of()));
        expectIllegalArgument(() -> new MyBatisDatabaseSnapshot(
                "main", "", MyBatisSqlDialect.GENERIC,
                MyBatisMetadataFreshness.READY, 0, List.of()));
        expectIllegalArgument(() -> new MyBatisDatabaseSnapshot(
                "main", "Main", MyBatisSqlDialect.GENERIC,
                MyBatisMetadataFreshness.READY, -1, List.of()));

        List<String> failedProviders = new ArrayList<>();
        MyBatisDatabaseMetadataResult.Failed failed =
                new MyBatisDatabaseMetadataResult.Failed(failedProviders);
        failedProviders.add("late");
        assertEmpty(failed.providerIds());
        assertTrue(MyBatisDatabaseRequest.all().dataSourceId().isEmpty());
        assertTrue(MyBatisDatabaseRequest.all().schemaName().isEmpty());
    }

    private MyBatisDatabaseMetadataService service() {
        return MyBatisDatabaseMetadataService.getInstance(getProject());
    }

    private void register(MyBatisDatabaseMetadataProvider provider) {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint()
                .registerExtension(provider, getTestRootDisposable());
    }

    private static MyBatisDatabaseMetadataProvider blockingProvider(
            CountDownLatch started,
            CountDownLatch cancelled) {
        return new MyBatisDatabaseMetadataProvider() {
            @Override
            public String id() {
                return "blocking";
            }

            @Override
            public List<MyBatisDatabaseSnapshot> load(
                    Project project,
                    MyBatisDatabaseRequest request,
                    ProgressIndicator indicator) {
                started.countDown();
                while (!indicator.isCanceled()) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                }
                cancelled.countDown();
                indicator.checkCanceled();
                return List.of();
            }
        };
    }

    private static MyBatisDatabaseSnapshot snapshot(
            String id,
            String name,
            String schema,
            String table) {
        MyBatisDatabaseColumn column = new MyBatisDatabaseColumn(
                "id",
                "BIGINT",
                java.sql.Types.BIGINT,
                false,
                true,
                false,
                1);
        return new MyBatisDatabaseSnapshot(
                id,
                name,
                MyBatisSqlDialect.GENERIC,
                MyBatisMetadataFreshness.READY,
                1,
                List.of(new MyBatisDatabaseTable(
                        Optional.empty(),
                        Optional.of(schema),
                        table,
                        List.of(column))));
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("无效元数据必须抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 元数据边界拒绝无效输入。
        }
    }
}
