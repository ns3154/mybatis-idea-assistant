package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbTable;
import com.intellij.database.view.DatabaseView;
import com.intellij.history.LocalHistory;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationCommandExecutor;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanner;
import io.github.ns3154.mybatisassistant.generator.MyBatisMethodPlanIntegrator;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisMethodGeneration;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 从单张已加载表解析方法名，并把方法签名与 XML statement 原子写入 S8 计划。
 */
public final class MyBatisDatabaseMethodGenerateAction extends AnAction {
    public static final String ID = "MyBatisAssistant.Database.GenerateMethod";

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
        DbTable[] selected = selectedTables(event);
        if (project == null || selected.length != 1) {
            return;
        }
        VirtualFile projectRoot = ProjectUtil.guessProjectDir(project);
        if (projectRoot == null || !projectRoot.isDirectory() || !projectRoot.isWritable()) {
            Messages.showErrorDialog(project,
                    MyBatisAssistantBundle.message("database.generation.error.project.root"),
                    MyBatisAssistantBundle.message("database.method.error.unavailable"));
            return;
        }
        String methodName = Messages.showInputDialog(
                project,
                MyBatisAssistantBundle.message("database.method.input.prompt"),
                MyBatisAssistantBundle.message("database.method.input.title"),
                Messages.getQuestionIcon());
        if (methodName == null) {
            return;
        }
        MyBatisGenerationOptionsDialog optionsDialog =
                new MyBatisGenerationOptionsDialog(project);
        if (!optionsDialog.showAndGet()) {
            return;
        }
        MyBatisGenerationConfiguration configuration = optionsDialog.configuration();
        try {
            requireTargets(configuration);
            MyBatisDatabaseMethodGenerationModel methodModel = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> {
                                ProgressIndicator indicator = ProgressManager.getInstance()
                                        .getProgressIndicator();
                                if (indicator == null) {
                                    throw new IllegalStateException(MyBatisAssistantBundle.message(
                                            "database.method.error.progress.context"));
                                }
                                return prepareMethodModel(
                                        selected[0],
                                        configuration,
                                        methodName.trim(),
                                        indicator);
                            },
                            MyBatisAssistantBundle.message("database.method.progress"),
                            true,
                            project);
            Optional<Set<Integer>> optionalConditionIndexes =
                    MyBatisOptionalConditionDialog.select(project, methodModel.query());
            if (optionalConditionIndexes.isEmpty()) {
                return;
            }
            MyBatisGenerationPlan plan = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> buildPlan(
                                    project,
                                    projectRoot,
                                    methodModel,
                                    optionalConditionIndexes.orElseThrow()),
                            MyBatisAssistantBundle.message("database.method.progress.plan"),
                            true,
                            project);
            MyBatisGenerationPreviewDialog preview =
                    new MyBatisGenerationPreviewDialog(project, plan);
            if (!preview.showAndGet()) {
                return;
            }
            MyBatisGenerationPlan selectedPlan = preview.selectedPlan();
            requireSelectedTargets(selectedPlan);
            LocalHistory.getInstance().putSystemLabel(
                    project, MyBatisAssistantBundle.message(
                            "database.method.local.history.before"));
            MyBatisGenerationCommandExecutor.execute(project, projectRoot, selectedPlan);
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("MyBatis Assistant")
                    .createNotification(
                            MyBatisAssistantBundle.message("database.method.success.title"),
                            MyBatisAssistantBundle.message("database.method.success"),
                            NotificationType.INFORMATION)
                    .notify(project);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            Messages.showErrorDialog(
                    project,
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage(),
                    MyBatisAssistantBundle.message("database.method.error.title"));
        }
    }

    static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName) {
        return buildPlan(
                project,
                projectRoot,
                table,
                dialect,
                configuration,
                methodName,
                Set.of());
    }

    static @NotNull MyBatisDatabaseMethodGenerationModel prepareMethodModel(
            @NotNull DbTable table,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull ProgressIndicator indicator) {
        return MyBatisDatabaseMethodGenerationModel.prepareDatabaseTable(
                table, configuration, methodName, indicator);
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

    static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull String methodName,
            @NotNull Set<Integer> optionalConditionIndexes) {
        requireTargets(configuration);
        MyBatisDatabaseMethodGenerationModel methodModel =
                MyBatisDatabaseMethodGenerationModel.prepare(
                        table, dialect, configuration, methodName);
        return buildPlan(
                project, projectRoot, methodModel, optionalConditionIndexes);
    }

    private static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull MyBatisDatabaseMethodGenerationModel methodModel,
            @NotNull Set<Integer> optionalConditionIndexes) {
        Set<Integer> validatedIndexes = MyBatisOptionalConditionSelectionModel
                .from(methodModel.query())
                .validatedIndexes(optionalConditionIndexes);
        MyBatisMethodGeneration generation =
                methodModel.generateMethod(validatedIndexes);
        MyBatisGenerationPlan base = MyBatisGenerationPlanner.plan(
                project, projectRoot, List.of(methodModel.bundle()));
        return MyBatisMethodPlanIntegrator.integrate(project, base, generation);
    }

    private static void requireTargets(@NotNull MyBatisGenerationConfiguration configuration) {
        if (!configuration.artifacts().contains(MyBatisGenerationArtifactKind.MAPPER)
                || !configuration.artifacts().contains(MyBatisGenerationArtifactKind.XML)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.method.error.targets.required"));
        }
    }

    private static void requireSelectedTargets(@NotNull MyBatisGenerationPlan plan) {
        boolean mapper = plan.entries().stream().anyMatch(entry ->
                entry.artifact().kind() == MyBatisGenerationArtifactKind.MAPPER);
        boolean xml = plan.entries().stream().anyMatch(entry ->
                entry.artifact().kind() == MyBatisGenerationArtifactKind.XML);
        if (!mapper || !xml) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "database.method.error.preview.targets.required"));
        }
    }

    private static @NotNull DbTable[] selectedTables(@NotNull AnActionEvent event) {
        DbElement[] elements = event.getData(DatabaseView.DB_ELEMENTS);
        if (elements == null || elements.length == 0
                || Arrays.stream(elements).anyMatch(element -> !(element instanceof DbTable))) {
            return new DbTable[0];
        }
        return Arrays.stream(elements).map(DbTable.class::cast).toArray(DbTable[]::new);
    }
}
