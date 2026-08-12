package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiMethod;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationCommandExecutor;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import io.github.ns3154.mybatisassistant.sqltool.annotation.MyBatisAnnotationSqlMigrationPlanner;
import org.jetbrains.annotations.NotNull;

/**
 * 将保守子集内的 MyBatis 注解 SQL 原子迁移到唯一 Mapper XML。
 */
public final class MyBatisAnnotationSqlMigrateAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.MigrateAnnotationSql";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiMethod method = MyBatisMapperJUnitSkeletonAction.selectedMethod(event);
        boolean available = project != null
                && project.isOpen()
                && !project.isDisposed()
                && !DumbService.isDumb(project)
                && method != null;
        if (available) {
            try {
                available = MyBatisAnnotationModel.statementSource(method)
                        == MyBatisStatementSourceKind.ANNOTATION_SQL;
            } catch (com.intellij.openapi.project.IndexNotReadyException ignored) {
                available = false;
            }
        }
        event.getPresentation().setEnabledAndVisible(available);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiMethod method = MyBatisMapperJUnitSkeletonAction.selectedMethod(event);
        if (project == null || method == null) {
            return;
        }
        VirtualFile projectRoot = ProjectUtil.guessProjectDir(project);
        if (projectRoot == null) {
            Messages.showErrorDialog(project, "无法确定项目目录", "注解 SQL 迁移未执行");
            return;
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        MyBatisAnnotationSqlMigrationPlanner.Result result = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                        () -> ReadAction.computeCancellable(
                                () -> MyBatisAnnotationSqlMigrationPlanner.plan(
                                        method,
                                        projectRoot)),
                        "分析注解 SQL 迁移",
                        true,
                        project);
        if (result instanceof MyBatisAnnotationSqlMigrationPlanner.Result.Failure failure) {
            Messages.showErrorDialog(project, failure.message(), "注解 SQL 迁移未执行");
            return;
        }
        MyBatisAnnotationSqlMigrationPlanner.Result.Success success =
                (MyBatisAnnotationSqlMigrationPlanner.Result.Success) result;
        if (!new MyBatisAnnotationMigrationPreviewDialog(
                project,
                success.plan()).showAndGet()) {
            return;
        }
        try {
            MyBatisGenerationCommandExecutor.execute(
                    project,
                    projectRoot,
                    success.plan(),
                    "迁移 MyBatis 注解 SQL 到 XML");
            Messages.showInfoMessage(
                    project,
                    "已迁移 " + success.namespace() + '#' + success.statementId()
                            + "；可使用一次 Undo 同时恢复 Java 与 XML。",
                    "注解 SQL 迁移完成");
        } catch (IllegalStateException failure) {
            Messages.showErrorDialog(
                    project,
                    failure.getMessage(),
                    "注解 SQL 迁移未执行");
        }
    }
}
