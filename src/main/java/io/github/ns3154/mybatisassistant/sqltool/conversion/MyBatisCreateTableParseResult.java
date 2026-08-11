package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * CREATE TABLE 解析结果；成功中的告警要求在预览中明确展示。
 */
public sealed interface MyBatisCreateTableParseResult {
    record Success(
            @NotNull MyBatisDatabaseTable table,
            @NotNull List<String> warnings,
            boolean confirmationRequired) implements MyBatisCreateTableParseResult {
        public Success {
            warnings = List.copyOf(warnings);
        }
    }

    record Failure(
            @NotNull MyBatisDdlDiagnosticCode code,
            int offset,
            @NotNull String message) implements MyBatisCreateTableParseResult {
    }
}
