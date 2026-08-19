package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbTable;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisWrapperFramework;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisWrapperGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisWrapperGenerationRequest;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisWrapperGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 从一张已加载表生成可编译 Wrapper 片段，只预览而不写入项目。
 */
public final class MyBatisDatabaseWrapperGenerateAction extends AnAction {
    public static final String ID = "MyBatisAssistant.Database.GenerateWrapper";
    private static final String PLUS = "MyBatis-Plus";
    private static final String FLEX = "MyBatis-Flex";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] tables = selectedTables(event);
        boolean enabled = project != null && tables.length == 1
                && tables[0].isValid() && !tables[0].getDataSource().isLoading();
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] tables = selectedTables(event);
        if (project == null || tables.length != 1) {
            return;
        }
        String methodName = Messages.showInputDialog(
                project,
                MyBatisAssistantBundle.message("database.wrapper.method.prompt"),
                MyBatisAssistantBundle.message("database.wrapper.title"),
                Messages.getQuestionIcon());
        if (methodName == null) {
            return;
        }
        MyBatisChoiceDialog<MyBatisWrapperFramework> frameworkDialog =
                new MyBatisChoiceDialog<>(
                project,
                MyBatisAssistantBundle.message("database.wrapper.title"),
                MyBatisAssistantBundle.message("database.wrapper.framework.prompt"),
                List.of(
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        MyBatisWrapperFramework.MYBATIS_FLEX),
                framework -> framework == MyBatisWrapperFramework.MYBATIS_PLUS
                        ? PLUS : FLEX);
        if (!frameworkDialog.showAndGet()) {
            return;
        }
        MyBatisWrapperFramework framework = frameworkDialog.selectedValue();
        String defaultVersion = framework == MyBatisWrapperFramework.MYBATIS_PLUS
                ? "3.5.17" : "1.11.8";
        String version = Messages.showInputDialog(
                project,
                MyBatisAssistantBundle.message("database.wrapper.version.prompt"),
                MyBatisAssistantBundle.message("database.wrapper.title"),
                Messages.getQuestionIcon(),
                defaultVersion,
                null);
        if (version == null) {
            return;
        }
        MyBatisGenerationOptionsDialog options = new MyBatisGenerationOptionsDialog(project);
        if (!options.showAndGet()) {
            return;
        }
        try {
            MyBatisDatabaseMethodGenerationModel methodModel = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> {
                                ProgressIndicator indicator = ProgressManager.getInstance()
                                        .getProgressIndicator();
                                if (indicator == null) {
                                    throw new IllegalStateException(MyBatisAssistantBundle.message(
                                            "database.wrapper.error.progress.context"));
                                }
                                return prepareMethodModel(
                                        tables[0],
                                        options.configuration(),
                                        methodName.trim(),
                                        indicator);
                            },
                            MyBatisAssistantBundle.message("database.wrapper.progress"),
                            true,
                            project);
            Optional<Set<Integer>> optionalConditionIndexes =
                    MyBatisOptionalConditionDialog.select(project, methodModel.query());
            if (optionalConditionIndexes.isEmpty()) {
                return;
            }
            WrapperPreview preview = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> buildPreview(
                                    methodModel,
                                    framework,
                                    version.trim(),
                                    optionalConditionIndexes.orElseThrow()),
                            MyBatisAssistantBundle.message(
                                    "database.wrapper.progress.preview"),
                            true,
                            project);
            new MyBatisGeneratedTextPreviewDialog(
                    project, MyBatisAssistantBundle.message(
                            "database.wrapper.preview.title"), preview.text()).show();
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            Messages.showErrorDialog(
                    project,
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage(),
                    MyBatisAssistantBundle.message("database.wrapper.error.title"));
        }
    }

    static @NotNull WrapperPreview buildPreview(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull MyBatisWrapperFramework framework,
            @NotNull String frameworkVersion) {
        return buildPreview(
                table,
                dialect,
                configuration,
                methodName,
                framework,
                frameworkVersion,
                Set.of());
    }

    static @NotNull MyBatisDatabaseMethodGenerationModel prepareMethodModel(
            @NotNull DbTable table,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull ProgressIndicator indicator,
            @NotNull MyBatisDatabaseMethodGenerationModel.SnapshotFactory snapshotFactory,
            @NotNull MyBatisDatabaseMethodGenerationModel.PreparationFactory preparationFactory) {
        return MyBatisDatabaseMethodGenerationModel.prepareDatabaseTable(
                table,
                configuration,
                methodName,
                indicator,
                snapshotFactory,
                preparationFactory);
    }

    private static @NotNull MyBatisDatabaseMethodGenerationModel prepareMethodModel(
            @NotNull DbTable table,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull ProgressIndicator indicator) {
        return MyBatisDatabaseMethodGenerationModel.prepareDatabaseTable(
                table, configuration, methodName, indicator);
    }

    static @NotNull WrapperPreview buildPreview(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull MyBatisWrapperFramework framework,
            @NotNull String frameworkVersion,
            @NotNull Set<Integer> optionalConditionIndexes) {
        MyBatisDatabaseMethodGenerationModel methodModel =
                MyBatisDatabaseMethodGenerationModel.prepare(
                        table, dialect, configuration, methodName);
        return buildPreview(
                methodModel, framework, frameworkVersion, optionalConditionIndexes);
    }

    private static @NotNull WrapperPreview buildPreview(
            @NotNull MyBatisDatabaseMethodGenerationModel methodModel,
            @NotNull MyBatisWrapperFramework framework,
            @NotNull String frameworkVersion,
            @NotNull Set<Integer> optionalConditionIndexes) {
        Set<Integer> validatedIndexes = MyBatisOptionalConditionSelectionModel
                .from(methodModel.query())
                .validatedIndexes(optionalConditionIndexes);
        MyBatisMethodGeneration method = methodModel.generateMethod(validatedIndexes);
        MyBatisWrapperGeneration wrapper = MyBatisWrapperGenerator.generate(
                new MyBatisWrapperGenerationRequest(
                        methodModel.schema(),
                        methodModel.query(),
                        method,
                        framework,
                        frameworkVersion,
                        methodModel.entityType(),
                        methodModel.dialect(),
                        methodModel.configuration().escapeSqlKeywords(),
                        validatedIndexes));
        return new WrapperPreview(
                method.javaMethod(), method.xmlStatement(), wrapper.code());
    }

    private static @NotNull DbTable[] selectedTables(@NotNull AnActionEvent event) {
        DbElement[] elements = event.getData(DatabaseView.DB_ELEMENTS);
        if (elements == null || elements.length == 0
                || Arrays.stream(elements).anyMatch(element -> !(element instanceof DbTable))) {
            return new DbTable[0];
        }
        return Arrays.stream(elements).map(DbTable.class::cast).toArray(DbTable[]::new);
    }

    record WrapperPreview(
            @NotNull String methodDeclaration,
            @NotNull String xmlStatement,
        @NotNull String wrapperCode) {
        private @NotNull String text() {
            return MyBatisAssistantBundle.message(
                    "database.wrapper.preview.mapper.comment") + '\n' + methodDeclaration
                    + "\n\n<!-- XML statement -->\n" + xmlStatement
                    + '\n' + MyBatisAssistantBundle.message(
                            "database.wrapper.preview.fragment.comment") + '\n' + wrapperCode;
        }
    }
}
