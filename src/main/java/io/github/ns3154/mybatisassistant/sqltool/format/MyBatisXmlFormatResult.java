package io.github.ns3154.mybatisassistant.sqltool.format;

import org.jetbrains.annotations.NotNull;

/**
 * 格式化结果；失败不携带任何部分输出。
 */
public sealed interface MyBatisXmlFormatResult {
    record Success(@NotNull String text) implements MyBatisXmlFormatResult {
    }

    record Failure(
            @NotNull MyBatisXmlFormatDiagnosticCode code,
            int lineNumber,
            @NotNull String message) implements MyBatisXmlFormatResult {
    }
}
