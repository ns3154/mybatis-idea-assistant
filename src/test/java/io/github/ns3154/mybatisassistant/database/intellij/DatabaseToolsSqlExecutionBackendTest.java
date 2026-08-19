package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.remote.jdbc.RemoteConnection;
import com.intellij.database.remote.jdbc.RemotePreparedStatement;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.remote.jdbc.RemoteResultSetMetaData;
import com.intellij.database.util.GuardedRef;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisAuthorizedSqlExecution;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionAuthorization;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPlan;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPolicy;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPreparation;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class DatabaseToolsSqlExecutionBackendTest extends BasePlatformTestCase {
    public void testExecutesReadOnlyQueryAndRollsBackDedicatedTransaction() {
        StubJdbc jdbc = StubJdbc.query(
                List.of("id", "payload"),
                List.of(List.of(1L, new byte[]{1, 2}), Arrays.asList(2L, null)));
        DatabaseToolsSqlExecutionBackend backend = backend(jdbc);

        MyBatisSqlExecutionResult result = backend.execute(
                authorized("select id, payload from users where id = ?", "LONG:7"),
                new EmptyProgressIndicator());

        MyBatisSqlExecutionResult.Success success =
                (MyBatisSqlExecutionResult.Success) result;
        assertEquals(List.of("id", "payload"), success.columns());
        assertEquals(List.of("1", "<BINARY 2 B>"), success.rows().get(0));
        assertEquals(List.of("2", "<NULL>"), success.rows().get(1));
        assertFalse(success.truncated());
        assertEquals(List.of(7L), jdbc.boundValues);
        assertTrue(jdbc.readOnly.get());
        assertTrue(jdbc.rolledBack.get());
        assertFalse(jdbc.committed.get());
        assertTrue(jdbc.statementClosed.get());
        assertTrue(jdbc.resultSetClosed.get());
        assertTrue(jdbc.guardClosed.get());
        assertFalse(success.toString().contains("BINARY"));
        assertFalse(success.toString().contains("payload"));
    }

    public void testExecutesAuthorizedUpdateAndCommits() {
        StubJdbc jdbc = StubJdbc.update(3);
        DatabaseToolsSqlExecutionBackend backend = backend(jdbc);

        MyBatisSqlExecutionResult result = backend.execute(
                authorized("update users set name = ? where id = ?",
                        "STRING:李四\nLONG:9"),
                new EmptyProgressIndicator());

        MyBatisSqlExecutionResult.Success success =
                (MyBatisSqlExecutionResult.Success) result;
        assertEquals(3, success.updateCount());
        assertEquals(List.of("李四", 9L), jdbc.boundValues);
        assertTrue(jdbc.committed.get());
        assertFalse(jdbc.readOnly.get());
        assertFalse(jdbc.rolledBack.get());
    }

    public void testRollsBackAndReturnsTypedDatabaseFailure() {
        StubJdbc jdbc = StubJdbc.failure(new SQLException("约束冲突", "23505", 1062));
        DatabaseToolsSqlExecutionBackend backend = backend(jdbc);

        MyBatisSqlExecutionResult result = backend.execute(
                authorized("delete from users where id = ?", "LONG:9"),
                new EmptyProgressIndicator());

        MyBatisSqlExecutionResult.Failure failure =
                (MyBatisSqlExecutionResult.Failure) result;
        assertEquals("约束冲突", failure.message());
        assertEquals("23505", failure.sqlState());
        assertEquals(1062, failure.vendorCode());
        assertTrue(jdbc.rolledBack.get());
        assertFalse(jdbc.committed.get());
        assertFalse(failure.toString().contains("约束冲突"));
    }

    public void testCancellationBeforeOpeningConnectionPropagates() {
        AtomicBoolean opened = new AtomicBoolean();
        DatabaseToolsSqlExecutionBackend backend = new DatabaseToolsSqlExecutionBackend(
                () -> {
                    opened.set(true);
                    throw new SQLException("不应打开");
                }, System::nanoTime);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        indicator.cancel();

        assertThrows(ProcessCanceledException.class, () -> backend.execute(
                authorized("select 1", ""), indicator));
        assertFalse(opened.get());
    }

    public void testCancellationInterruptsBlockingRemoteStatement() throws Exception {
        StubJdbc jdbc = StubJdbc.blockingQuery();
        DatabaseToolsSqlExecutionBackend backend = backend(jdbc);
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                backend.execute(authorized("select 1", ""), indicator);
            } catch (RuntimeException thrown) {
                failure.set(thrown);
            }
        }, "sql-execution-cancellation-test");

        worker.start();
        assertTrue(jdbc.executeStarted.await(2, TimeUnit.SECONDS));
        indicator.cancel();
        worker.join(3_000);

        assertFalse(worker.isAlive());
        assertTrue(jdbc.cancelCalled.get());
        assertInstanceOf(failure.get(), ProcessCanceledException.class);
        assertTrue(jdbc.rolledBack.get());
    }

    public void testRedactsBinaryAndTruncatesOversizedCells() {
        assertEquals("<BINARY 3 B>",
                DatabaseToolsSqlExecutionBackend.renderCell(new byte[]{1, 2, 3}));
        String rendered = DatabaseToolsSqlExecutionBackend.renderCell(
                "x".repeat(DatabaseToolsSqlExecutionBackend.MAX_CELL_CHARS + 1));
        assertTrue(rendered.endsWith("…<已截断>"));
        assertEquals(DatabaseToolsSqlExecutionBackend.MAX_CELL_CHARS + 6,
                rendered.length());
    }

    public void testRejectsExcessiveResultColumnsAndRollsBack() {
        List<String> columns = new ArrayList<>();
        for (int index = 0; index <= DatabaseToolsSqlExecutionBackend.MAX_COLUMNS; index++) {
            columns.add("column" + index);
        }
        StubJdbc jdbc = StubJdbc.query(columns, List.of());

        MyBatisSqlExecutionResult result = backend(jdbc).execute(
                authorized("select 1", ""), new EmptyProgressIndicator());

        assertInstanceOf(result, MyBatisSqlExecutionResult.Failure.class);
        assertTrue(jdbc.rolledBack.get());
        assertFalse(jdbc.committed.get());
    }

    private static DatabaseToolsSqlExecutionBackend backend(StubJdbc jdbc) {
        return new DatabaseToolsSqlExecutionBackend(jdbc::guardedConnection,
                new IncrementingClock());
    }

    private static MyBatisAuthorizedSqlExecution authorized(String sql, String parameters) {
        MyBatisSqlExecutionPreparation preparation =
                MyBatisSqlExecutionPolicy.prepare(sql, parameters);
        MyBatisSqlExecutionPlan plan =
                ((MyBatisSqlExecutionPreparation.Ready) preparation).plan();
        MyBatisSqlExecutionAuthorization authorization =
                MyBatisSqlExecutionPolicy.authorize(
                        plan,
                        plan.doubleConfirmationRequired(),
                        plan.doubleConfirmationRequired()
                                ? MyBatisSqlExecutionPolicy.dangerousConfirmationPhrase() : "");
        return ((MyBatisSqlExecutionAuthorization.Authorized) authorization).execution();
    }

    private static final class IncrementingClock implements java.util.function.LongSupplier {
        private long value;

        @Override
        public long getAsLong() {
            value += 1_000_000L;
            return value;
        }
    }

    private static final class StubJdbc {
        private final AtomicBoolean readOnly = new AtomicBoolean();
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean rolledBack = new AtomicBoolean();
        private final AtomicBoolean statementClosed = new AtomicBoolean();
        private final AtomicBoolean resultSetClosed = new AtomicBoolean();
        private final AtomicBoolean guardClosed = new AtomicBoolean();
        private final AtomicBoolean cancelCalled = new AtomicBoolean();
        private final CountDownLatch executeStarted = new CountDownLatch(1);
        private final List<Object> boundValues = new ArrayList<>();
        private final List<String> columns;
        private final List<List<Object>> rows;
        private final int updateCount;
        private final SQLException executeFailure;
        private final boolean blockUntilCanceled;

        private StubJdbc(
                List<String> columns,
                List<List<Object>> rows,
                int updateCount,
                SQLException executeFailure,
                boolean blockUntilCanceled) {
            this.columns = columns;
            this.rows = rows;
            this.updateCount = updateCount;
            this.executeFailure = executeFailure;
            this.blockUntilCanceled = blockUntilCanceled;
        }

        static StubJdbc query(List<String> columns, List<List<Object>> rows) {
            return new StubJdbc(columns, rows, -1, null, false);
        }

        static StubJdbc update(int count) {
            return new StubJdbc(List.of(), List.of(), count, null, false);
        }

        static StubJdbc failure(SQLException failure) {
            return new StubJdbc(List.of(), List.of(), -1, failure, false);
        }

        static StubJdbc blockingQuery() {
            return new StubJdbc(List.of("value"), List.of(), -1, null, true);
        }

        GuardedRef<DatabaseConnection> guardedConnection() {
            DatabaseConnection connection = proxy(
                    DatabaseConnection.class, this::connectionCall);
            return new GuardedRef<>(connection) {
                @Override
                protected void close(DatabaseConnection ignored) {
                    guardClosed.set(true);
                }
            };
        }

        private Object connectionCall(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            return switch (method.getName()) {
                case "setReadOnly" -> {
                    readOnly.set((Boolean) arguments[0]);
                    yield null;
                }
                case "setAutoCommit" -> null;
                case "rollback" -> {
                    rolledBack.set(true);
                    yield null;
                }
                case "commit" -> {
                    committed.set(true);
                    yield null;
                }
                case "getRemoteConnection" -> proxy(
                        RemoteConnection.class, this::remoteConnectionCall);
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object remoteConnectionCall(Object proxy, Method method, Object[] arguments) {
            if ("prepareStatement".equals(method.getName())) {
                return proxy(RemotePreparedStatement.class, this::statementCall);
            }
            return defaultValue(method.getReturnType());
        }

        private Object statementCall(Object proxy, Method method, Object[] arguments)
                throws Throwable {
            String name = method.getName();
            if (name.startsWith("set") && arguments != null && arguments.length >= 2
                    && arguments[0] instanceof Integer jdbcIndex) {
                while (boundValues.size() < jdbcIndex) {
                    boundValues.add(null);
                }
                boundValues.set(jdbcIndex - 1,
                        "setNull".equals(name) ? null : arguments[1]);
                return null;
            }
            return switch (name) {
                case "execute" -> {
                    executeStarted.countDown();
                    if (blockUntilCanceled) {
                        while (!cancelCalled.get()) {
                            Thread.sleep(10);
                        }
                        throw new SQLException("查询已取消", "57014");
                    }
                    if (executeFailure != null) {
                        throw executeFailure;
                    }
                    yield !columns.isEmpty();
                }
                case "cancel" -> {
                    cancelCalled.set(true);
                    yield null;
                }
                case "getResultSet" -> proxy(RemoteResultSet.class,
                        new ResultSetHandler(this));
                case "getUpdateCount" -> updateCount;
                case "close" -> {
                    statementClosed.set(true);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final StubJdbc jdbc;
        private final AtomicInteger cursor = new AtomicInteger(-1);

        private ResultSetHandler(StubJdbc jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "next" -> cursor.incrementAndGet() < jdbc.rows.size();
                case "getObject" -> jdbc.rows.get(cursor.get())
                        .get((Integer) arguments[0] - 1);
                case "getMetaData" -> proxy(RemoteResultSetMetaData.class,
                        (metadata, metadataMethod, metadataArguments) -> switch (
                                metadataMethod.getName()) {
                            case "getColumnCount" -> jdbc.columns.size();
                            case "getColumnLabel" -> jdbc.columns.get(
                                    (Integer) metadataArguments[0] - 1);
                            default -> defaultValue(metadataMethod.getReturnType());
                        });
                case "close" -> {
                    jdbc.resultSetClosed.set(true);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
