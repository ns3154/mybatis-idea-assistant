package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbTable;
import com.intellij.openapi.progress.ProgressIndicator;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodDiagnostic;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodNameParser;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodParseResult;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodQuery;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSchema;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodSqlGenerator;
import io.github.ns3154.mybatisassistant.util.MyBatisReadActionSupport;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * 方法名和 Wrapper 用户入口共享的解析结果，避免两条 UI 路径重新解释同一 AST。
 */
record MyBatisDatabaseMethodGenerationModel(
        @NotNull MyBatisGenerationBundle bundle,
        @NotNull MyBatisMethodSchema schema,
        @NotNull MyBatisMethodQuery query,
        @NotNull MyBatisSqlDialect dialect,
        @NotNull MyBatisGenerationConfiguration configuration,
        @NotNull String entityType) {

    static @NotNull MyBatisDatabaseMethodGenerationModel prepareDatabaseTable(
            @NotNull DbTable table,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull ProgressIndicator indicator) {
        return prepareDatabaseTable(
                table,
                configuration,
                methodName,
                indicator,
                MyBatisDatabaseMethodGenerationModel::snapshot,
                MyBatisDatabaseMethodGenerationModel::prepareSnapshot);
    }

    static @NotNull MyBatisDatabaseMethodGenerationModel prepareDatabaseTable(
            @NotNull DbTable table,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull ProgressIndicator indicator,
            @NotNull SnapshotFactory snapshotFactory,
            @NotNull PreparationFactory preparationFactory) {
        DatabaseSnapshot snapshot = MyBatisReadActionSupport.compute(() -> {
            indicator.checkCanceled();
            if (!table.isValid() || table.getDataSource().isLoading()) {
                throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "database.generation.error.model.changed"));
            }
            return snapshotFactory.snapshot(table, indicator);
        });
        indicator.checkCanceled();
        return preparationFactory.prepare(snapshot, configuration, methodName);
    }

    static @NotNull MyBatisDatabaseMethodGenerationModel prepare(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName) {
        MyBatisGenerationBundle bundle = MyBatisGenerationEngine.generate(
                new MyBatisGenerationRequest(
                        "database-method", dialect, table, configuration));
        MyBatisMethodSchema schema = MyBatisMethodSchema.from(table, configuration);
        MyBatisMethodParseResult parsed = MyBatisMethodNameParser.parse(methodName, schema);
        if (parsed instanceof MyBatisMethodParseResult.Failure failure) {
            MyBatisMethodDiagnostic diagnostic = failure.diagnostic();
            throw new IllegalArgumentException(
                    diagnostic.message() + MyBatisAssistantBundle.message(
                            "diagnostic.offset.suffix", diagnostic.offset()));
        }
        String entityType = configuration.basePackage()
                + ".entity." + bundle.entityName();
        return new MyBatisDatabaseMethodGenerationModel(
                bundle,
                schema,
                ((MyBatisMethodParseResult.Success) parsed).query(),
                dialect,
                configuration,
                entityType);
    }

    private static @NotNull DatabaseSnapshot snapshot(
            @NotNull DbTable table,
            @NotNull ProgressIndicator indicator) {
        return new DatabaseSnapshot(
                DatabaseToolsMetadataProvider.table(table.getDasObject(), indicator),
                DatabaseToolsMetadataProvider.dialect(table.getDataSource().getDbms()));
    }

    private static @NotNull MyBatisDatabaseMethodGenerationModel prepareSnapshot(
            @NotNull DatabaseSnapshot snapshot,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName) {
        return prepare(snapshot.table(), snapshot.dialect(), configuration, methodName);
    }

    @NotNull MyBatisMethodGeneration generateMethod(
            @NotNull Set<Integer> optionalConditionIndexes) {
        return MyBatisMethodSqlGenerator.generate(new MyBatisMethodGenerationRequest(
                schema,
                query,
                dialect,
                entityType,
                configuration.escapeSqlKeywords(),
                optionalConditionIndexes));
    }

    record DatabaseSnapshot(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect) {
    }

    @FunctionalInterface
    interface SnapshotFactory {
        @NotNull DatabaseSnapshot snapshot(
                @NotNull DbTable table,
                @NotNull ProgressIndicator indicator);
    }

    @FunctionalInterface
    interface PreparationFactory {
        @NotNull MyBatisDatabaseMethodGenerationModel prepare(
                @NotNull DatabaseSnapshot snapshot,
                @NotNull MyBatisGenerationConfiguration configuration,
                @NotNull String methodName);
    }
}
