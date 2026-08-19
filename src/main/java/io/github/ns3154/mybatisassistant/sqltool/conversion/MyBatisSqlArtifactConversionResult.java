package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * DDL 到 MyBatis 产物的纯内存转换结果。
 */
public sealed interface MyBatisSqlArtifactConversionResult {
    record Success(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisGenerationBundle bundle,
            @NotNull List<String> warnings,
            boolean confirmationRequired) implements MyBatisSqlArtifactConversionResult {
        public Success {
            warnings = List.copyOf(warnings);
        }
    }

    record Failure(
            @NotNull MyBatisDdlDiagnosticCode code,
            int offset,
            @NotNull String message) implements MyBatisSqlArtifactConversionResult {
    }
}
