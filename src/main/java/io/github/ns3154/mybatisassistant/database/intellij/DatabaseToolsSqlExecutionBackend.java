package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.remote.jdbc.RemotePreparedStatement;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.remote.jdbc.RemoteResultSetMetaData;
import com.intellij.database.util.GuardedRef;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisAuthorizedSqlExecution;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionBackend;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPlan;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionResult;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlParameter;
import io.github.ns3154.mybatisassistant.sqltool.log.MyBatisSqlRisk;
import org.jetbrains.annotations.NotNull;

import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Database Tools 可选执行适配器。每次执行持有独立的受管连接引用，并明确结束事务。
 */
public final class DatabaseToolsSqlExecutionBackend implements MyBatisSqlExecutionBackend {
    static final int MAX_ROWS = 200;
    static final int MAX_COLUMNS = 256;
    static final int MAX_CELL_CHARS = 2_000;
    static final int QUERY_TIMEOUT_SECONDS = 30;
    private final ConnectionOpener opener;
    private final LongSupplier nanoTime;

    public DatabaseToolsSqlExecutionBackend(
            @NotNull Project project,
            @NotNull LocalDataSource dataSource) {
        this(() -> DatabaseConnectionManager.getInstance()
                .build(project, dataSource)
                .setAskPassword(true)
                .createBlocking(), System::nanoTime);
    }

    DatabaseToolsSqlExecutionBackend(
            @NotNull ConnectionOpener opener,
            @NotNull LongSupplier nanoTime) {
        this.opener = opener;
        this.nanoTime = nanoTime;
    }

    @Override
    public @NotNull MyBatisSqlExecutionResult execute(
            @NotNull MyBatisAuthorizedSqlExecution execution,
            @NotNull ProgressIndicator indicator) {
        indicator.checkCanceled();
        long started = nanoTime.getAsLong();
        try (GuardedRef<DatabaseConnection> guarded = opener.open()) {
            return executeOnConnection(
                    guarded.get(), execution.plan(), indicator, started);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (SQLException | RemoteException failure) {
            return failure(failure);
        } catch (RuntimeException failure) {
            return new MyBatisSqlExecutionResult.Failure(
                    safeMessage(failure), "", 0);
        }
    }

    private @NotNull MyBatisSqlExecutionResult executeOnConnection(
            @NotNull DatabaseConnection connection,
            @NotNull MyBatisSqlExecutionPlan plan,
            @NotNull ProgressIndicator indicator,
            long started) throws SQLException, RemoteException {
        boolean completed = false;
        RemotePreparedStatement statement = null;
        ScheduledFuture<?> cancellationWatcher = null;
        try {
            connection.setAutoCommit(false);
            if (plan.riskAssessment().risk() == MyBatisSqlRisk.READ_ONLY) {
                connection.setReadOnly(true);
            }
            statement = connection.getRemoteConnection().prepareStatement(plan.sql());
            statement.setMaxRows(MAX_ROWS + 1);
            statement.setFetchSize(Math.min(100, MAX_ROWS));
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bind(statement, plan.parameters(), indicator);
            indicator.checkCanceled();
            cancellationWatcher = watchCancellation(statement, indicator);
            boolean hasRows = statement.execute();
            indicator.checkCanceled();
            MyBatisSqlExecutionResult.Success result = hasRows
                    ? readRows(statement.getResultSet(), indicator, started)
                    : new MyBatisSqlExecutionResult.Success(
                            List.of(), List.of(), statement.getUpdateCount(), false,
                            elapsedMillis(started));
            if (plan.riskAssessment().risk() == MyBatisSqlRisk.READ_ONLY) {
                connection.rollback();
            } else {
                connection.commit();
            }
            completed = true;
            return result;
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (SQLException | RemoteException failure) {
            indicator.checkCanceled();
            return failure(failure);
        } finally {
            if (cancellationWatcher != null) {
                cancellationWatcher.cancel(true);
            }
            if (!completed) {
                rollbackQuietly(connection);
            }
            closeQuietly(statement);
        }
    }

    private static @NotNull ScheduledFuture<?> watchCancellation(
            @NotNull RemotePreparedStatement statement,
            @NotNull ProgressIndicator indicator) {
        return AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> {
                    if (!indicator.isCanceled()) {
                        return;
                    }
                    try {
                        statement.cancel();
                    } catch (SQLException | RemoteException ignored) {
                        // 取消失败仍由 30 秒查询超时兜底，不记录数据库上下文。
                    }
                },
                100,
                100,
                TimeUnit.MILLISECONDS);
    }

    private void bind(
            @NotNull RemotePreparedStatement statement,
            @NotNull List<MyBatisSqlParameter> parameters,
            @NotNull ProgressIndicator indicator) throws SQLException, RemoteException {
        for (int index = 0; index < parameters.size(); index++) {
            indicator.checkCanceled();
            MyBatisSqlParameter parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            switch (parameter.type()) {
                case STRING -> statement.setString(jdbcIndex, (String) parameter.value());
                case LONG -> statement.setLong(jdbcIndex, (Long) parameter.value());
                case DECIMAL -> statement.setBigDecimal(
                        jdbcIndex, (java.math.BigDecimal) parameter.value());
                case BOOLEAN -> statement.setBoolean(jdbcIndex, (Boolean) parameter.value());
                case DATE -> statement.setDate(
                        jdbcIndex, (java.sql.Date) parameter.value());
                case TIMESTAMP -> statement.setTimestamp(
                        jdbcIndex, (java.sql.Timestamp) parameter.value());
                case NULL -> statement.setNull(jdbcIndex, parameter.jdbcType());
            }
        }
    }

    private @NotNull MyBatisSqlExecutionResult.Success readRows(
            RemoteResultSet resultSet,
            @NotNull ProgressIndicator indicator,
            long started) throws SQLException, RemoteException {
        if (resultSet == null) {
            return new MyBatisSqlExecutionResult.Success(
                    List.of(), List.of(), -1, false, elapsedMillis(started));
        }
        try {
            RemoteResultSetMetaData metadata = resultSet.getMetaData();
            int columnCount = metadata.getColumnCount();
            if (columnCount > MAX_COLUMNS) {
                throw new SQLException(MyBatisAssistantBundle.message(
                        "database.sql.result.column.limit", MAX_COLUMNS));
            }
            List<String> columns = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                indicator.checkCanceled();
                String label = metadata.getColumnLabel(index);
                columns.add(label == null || label.isBlank()
                        ? MyBatisAssistantBundle.message("database.sql.result.column", index)
                        : label);
            }
            List<List<String>> rows = new ArrayList<>();
            boolean truncated = false;
            while (resultSet.next()) {
                indicator.checkCanceled();
                if (rows.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>(columnCount);
                for (int index = 1; index <= columnCount; index++) {
                    indicator.checkCanceled();
                    row.add(renderCell(resultSet.getObject(index)));
                }
                rows.add(row);
            }
            return new MyBatisSqlExecutionResult.Success(
                    columns, rows, -1, truncated, elapsedMillis(started));
        } finally {
            closeQuietly(resultSet);
        }
    }

    static @NotNull String renderCell(Object value) {
        if (value == null) {
            return "<NULL>";
        }
        if (value instanceof byte[] bytes) {
            return "<BINARY " + bytes.length + " B>";
        }
        String text = Objects.toString(value);
        if (text.length() <= MAX_CELL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CELL_CHARS)
                + MyBatisAssistantBundle.message("database.sql.result.cell.truncated");
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (nanoTime.getAsLong() - started) / 1_000_000L);
    }

    private static @NotNull MyBatisSqlExecutionResult.Failure failure(Exception failure) {
        if (failure instanceof SQLException sqlFailure) {
            return new MyBatisSqlExecutionResult.Failure(
                    safeMessage(sqlFailure), nullToEmpty(sqlFailure.getSQLState()),
                    sqlFailure.getErrorCode());
        }
        return new MyBatisSqlExecutionResult.Failure(safeMessage(failure), "", 0);
    }

    private static @NotNull String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static @NotNull String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void rollbackQuietly(DatabaseConnection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 原始执行失败优先返回；回滚失败不写入日志，连接引用随后释放。
        }
    }

    private static void closeQuietly(RemotePreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException | RemoteException ignored) {
            // 连接引用随后释放，不记录可能包含数据库上下文的异常。
        }
    }

    private static void closeQuietly(RemoteResultSet resultSet) {
        try {
            resultSet.close();
        } catch (SQLException | RemoteException ignored) {
            // 连接引用随后释放，不记录可能包含数据库上下文的异常。
        }
    }

    @FunctionalInterface
    interface ConnectionOpener {
        @NotNull GuardedRef<DatabaseConnection> open() throws SQLException;
    }
}
