package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

/**
 * 复用 S8 生成引擎，把 CREATE TABLE 转为 Entity、Mapper 与含 ResultMap 的 XML。
 */
public final class MyBatisSqlArtifactConverter {
    private MyBatisSqlArtifactConverter() {
    }

    public static @NotNull MyBatisSqlArtifactConversionResult convert(
            @NotNull String ddl,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull String basePackage) {
        MyBatisCreateTableParseResult parsed = MyBatisCreateTableParser.parse(ddl);
        if (parsed instanceof MyBatisCreateTableParseResult.Failure failure) {
            return new MyBatisSqlArtifactConversionResult.Failure(
                    failure.code(), failure.offset(), failure.message());
        }
        MyBatisCreateTableParseResult.Success success =
                (MyBatisCreateTableParseResult.Success) parsed;
        try {
            MyBatisGenerationConfiguration standard =
                    MyBatisGenerationConfiguration.standard(basePackage);
            MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                    standard.basePackage(),
                    standard.javaSourceRoot(),
                    standard.resourceRoot(),
                    EnumSet.of(
                            MyBatisGenerationArtifactKind.ENTITY,
                            MyBatisGenerationArtifactKind.MAPPER,
                            MyBatisGenerationArtifactKind.XML),
                    standard.templateGroup(),
                    standard.tablePrefix(),
                    standard.entitySuffix(),
                    standard.generateComments(),
                    standard.escapeSqlKeywords(),
                    standard.excludedColumns(),
                    standard.columnOverrides());
            MyBatisGenerationBundle bundle = MyBatisGenerationEngine.generate(
                    new MyBatisGenerationRequest(
                            "ddl-conversion", dialect, success.table(), configuration));
            return new MyBatisSqlArtifactConversionResult.Success(
                    success.table(), bundle, success.warnings(),
                    success.confirmationRequired());
        } catch (IllegalArgumentException invalid) {
            return new MyBatisSqlArtifactConversionResult.Failure(
                    MyBatisDdlDiagnosticCode.UNSUPPORTED_DEFINITION,
                    0,
                    invalid.getMessage() == null ? "生成配置或标识符不合法" : invalid.getMessage());
        }
    }
}
