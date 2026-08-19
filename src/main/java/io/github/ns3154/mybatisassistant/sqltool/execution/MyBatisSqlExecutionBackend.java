package io.github.ns3154.mybatisassistant.sqltool.execution;

import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;

/**
 * 数据库适配器的最小执行边界。
 */
public interface MyBatisSqlExecutionBackend {
    @NotNull MyBatisSqlExecutionResult execute(
            @NotNull MyBatisAuthorizedSqlExecution execution,
            @NotNull ProgressIndicator indicator);
}
