package io.github.ns3154.mybatisassistant.sqltool.conversion;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * SELECT 到 Mapper、XML、ResultMap 与 Java 行模型的转换结果。
 */
public sealed interface MyBatisSelectArtifactConversionResult {
    record Success(
            @NotNull String mapperSource,
            @NotNull String xmlSource,
            @NotNull String rowModelSource,
            @NotNull List<String> warnings) implements MyBatisSelectArtifactConversionResult {
        public Success {
            warnings = List.copyOf(warnings);
        }
    }

    record Failure(int offset, @NotNull String message)
            implements MyBatisSelectArtifactConversionResult {
    }
}
