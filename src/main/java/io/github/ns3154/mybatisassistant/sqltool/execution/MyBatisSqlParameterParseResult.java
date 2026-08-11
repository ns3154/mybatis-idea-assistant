package io.github.ns3154.mybatisassistant.sqltool.execution;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 参数面板解析结果。
 */
public sealed interface MyBatisSqlParameterParseResult {
    record Success(@NotNull List<MyBatisSqlParameter> parameters)
            implements MyBatisSqlParameterParseResult {
        public Success {
            parameters = List.copyOf(parameters);
        }
    }

    record Failure(int lineNumber, @NotNull String message)
            implements MyBatisSqlParameterParseResult {
    }
}
