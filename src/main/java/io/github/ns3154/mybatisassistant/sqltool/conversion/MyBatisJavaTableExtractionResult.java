package io.github.ns3154.mybatisassistant.sqltool.conversion;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Java PSI 到表模型的类型化结果。
 */
public sealed interface MyBatisJavaTableExtractionResult {
    record Success(
            @NotNull MyBatisJavaTableSchema table,
            @NotNull List<String> warnings,
            boolean confirmationRequired) implements MyBatisJavaTableExtractionResult {
        public Success {
            warnings = List.copyOf(warnings);
        }
    }

    record Failure(@NotNull String message) implements MyBatisJavaTableExtractionResult {
    }
}
