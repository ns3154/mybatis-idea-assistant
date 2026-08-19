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
import com.intellij.openapi.application.ApplicationManager;
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
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationCommandExecutor;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanner;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import io.github.ns3154.mybatisassistant.util.MyBatisReadActionSupport;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 只对 Database Tools 已加载且由用户选中的表生成代码，不触发连接或刷新。
 */
public final class MyBatisDatabaseGenerateAction extends AnAction {
    public static final String ID = "MyBatisAssistant.Database.Generate";
    private static final double BUNDLE_PROGRESS_END = 0.5;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] tables = selectedTables(event);
        boolean enabled = project != null
                && tables.length > 0
                && Arrays.stream(tables).allMatch(table -> table.isValid()
                        && !table.getDataSource().isLoading());
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbTable[] selected = selectedTables(event);
        if (project == null || selected.length == 0) {
            return;
        }
        VirtualFile projectRoot = ProjectUtil.guessProjectDir(project);
        if (projectRoot == null || !projectRoot.isDirectory() || !projectRoot.isWritable()) {
            Messages.showErrorDialog(project,
                    MyBatisAssistantBundle.message("database.generation.error.project.root"),
                    MyBatisAssistantBundle.message("database.generation.error.unavailable"));
            return;
        }
        MyBatisGenerationOptionsDialog optionsDialog =
                new MyBatisGenerationOptionsDialog(project);
        if (!optionsDialog.showAndGet()) {
            return;
        }
        MyBatisGenerationConfiguration configuration = optionsDialog.configuration();
        try {
            MyBatisGenerationPlan plan = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> buildPlan(project, projectRoot, selected, configuration),
                            MyBatisAssistantBundle.message("database.generation.progress"),
                            true,
                            project);
            MyBatisGenerationPreviewDialog previewDialog =
                    new MyBatisGenerationPreviewDialog(project, plan);
            if (!previewDialog.showAndGet()) {
                return;
            }
            MyBatisGenerationPlan selectedPlan = previewDialog.selectedPlan();
            if (!selectedPlan.hasChanges()) {
                return;
            }
            LocalHistory.getInstance().putSystemLabel(
                    project, MyBatisAssistantBundle.message(
                            "database.generation.local.history.before"));
            MyBatisGenerationCommandExecutor.execute(project, projectRoot, selectedPlan);
            int changed = (int) selectedPlan.entries().stream()
                    .filter(entry -> entry.status()
                            != io.github.ns3154.mybatisassistant.generator
                                    .MyBatisGenerationPlanStatus.UNCHANGED)
                    .count();
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("MyBatis Assistant")
                    .createNotification(
                            MyBatisAssistantBundle.message(
                                    "database.generation.success.title"),
                            MyBatisAssistantBundle.message(
                                    "database.generation.success", changed),
                            NotificationType.INFORMATION)
                    .notify(project);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            Messages.showErrorDialog(
                    project,
                    failure.getMessage() == null ? failure.getClass().getSimpleName()
                            : failure.getMessage(),
                    MyBatisAssistantBundle.message("database.generation.error.title"));
        }
    }

    private static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull DbTable[] selected,
            @NotNull MyBatisGenerationConfiguration configuration) {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator == null) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "database.generation.error.progress.context"));
        }
        return buildPlan(
                project,
                projectRoot,
                selected,
                configuration,
                indicator,
                MyBatisDatabaseGenerateAction::snapshot,
                MyBatisDatabaseGenerateAction::generateBundle);
    }

    static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull DbTable[] selected,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull ProgressIndicator indicator) {
        return buildPlan(
                project,
                projectRoot,
                selected,
                configuration,
                indicator,
                MyBatisDatabaseGenerateAction::snapshot,
                MyBatisDatabaseGenerateAction::generateBundle);
    }

    static @NotNull MyBatisGenerationPlan buildPlan(
            @NotNull Project project,
            @NotNull VirtualFile projectRoot,
            @NotNull DbTable[] selected,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull ProgressIndicator indicator,
            @NotNull MetadataSnapshotFactory snapshotFactory,
            @NotNull GenerationBundleFactory bundleFactory) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "database.generation.error.edt"));
        }
        indicator.setIndeterminate(false);
        indicator.setFraction(0.0);
        List<MyBatisGenerationBundle> bundles = new ArrayList<>();
        for (int index = 0; index < selected.length; index++) {
            indicator.checkCanceled();
            DbTable table = selected[index];
            DatabaseGenerationSnapshot snapshot = MyBatisReadActionSupport.compute(() -> {
                indicator.checkCanceled();
                ensureAvailable(table);
                return snapshotFactory.snapshot(table, indicator);
            });
            indicator.setText2(snapshot.tableName());
            indicator.checkCanceled();
            MyBatisGenerationBundle bundle = bundleFactory.generate(
                    snapshot, configuration, indicator);
            indicator.checkCanceled();
            bundles.add(bundle);
            indicator.setFraction(BUNDLE_PROGRESS_END * (index + 1.0) / selected.length);
        }
        indicator.setText2("");
        return MyBatisGenerationPlanner.plan(
                project,
                projectRoot,
                bundles,
                indicator,
                BUNDLE_PROGRESS_END);
    }

    private static void ensureAvailable(@NotNull DbTable table) {
        if (!table.isValid() || table.getDataSource().isLoading()) {
            throw new IllegalStateException(MyBatisAssistantBundle.message(
                    "database.generation.error.model.changed"));
        }
    }

    private static @NotNull DatabaseGenerationSnapshot snapshot(
            @NotNull DbTable table,
            @NotNull ProgressIndicator indicator) {
        MyBatisDatabaseTable model = DatabaseToolsMetadataProvider.table(
                table.getDasObject(), indicator);
        return new DatabaseGenerationSnapshot(
                table.getName(),
                table.getDataSource().getUniqueId(),
                DatabaseToolsMetadataProvider.dialect(
                        table.getDataSource().getDbms()),
                model);
    }

    private static @NotNull MyBatisGenerationBundle generateBundle(
            @NotNull DatabaseGenerationSnapshot snapshot,
            @NotNull MyBatisGenerationConfiguration configuration,
            @NotNull ProgressIndicator indicator) {
        indicator.checkCanceled();
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                snapshot.dataSourceId(),
                snapshot.dialect(),
                snapshot.table(),
                configuration));
    }

    private static @NotNull DbTable[] selectedTables(@NotNull AnActionEvent event) {
        DbElement[] elements = event.getData(DatabaseView.DB_ELEMENTS);
        if (elements == null || elements.length == 0
                || Arrays.stream(elements).anyMatch(element -> !(element instanceof DbTable))) {
            return new DbTable[0];
        }
        return Arrays.stream(elements)
                .map(DbTable.class::cast)
                .toArray(DbTable[]::new);
    }

    record DatabaseGenerationSnapshot(
            @NotNull String tableName,
            @NotNull String dataSourceId,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull MyBatisDatabaseTable table) {
    }

    @FunctionalInterface
    interface MetadataSnapshotFactory {
        @NotNull DatabaseGenerationSnapshot snapshot(
                @NotNull DbTable table,
                @NotNull ProgressIndicator indicator);
    }

    @FunctionalInterface
    interface GenerationBundleFactory {
        @NotNull MyBatisGenerationBundle generate(
                @NotNull DatabaseGenerationSnapshot snapshot,
                @NotNull MyBatisGenerationConfiguration configuration,
                @NotNull ProgressIndicator indicator);
    }

}
