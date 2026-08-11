package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisAuthorizedSqlExecution;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionAuthorization;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPlan;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPolicy;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPreparation;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionResult;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * 仅在 Database Tools 右键菜单出现的受控 SQL 执行入口。
 */
public final class MyBatisDatabaseSqlExecuteAction extends AnAction {
    public static final String ID = "MyBatisAssistant.Database.ExecuteSql";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbDataSource dataSource = selectedDataSource(event);
        event.getPresentation().setEnabledAndVisible(project != null
                && project.isOpen()
                && !project.isDisposed()
                && dataSource != null
                && dataSource.isValid()
                && !dataSource.isLoading()
                && localDataSource(dataSource) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        DbDataSource selected = selectedDataSource(event);
        LocalDataSource dataSource = selected == null ? null : localDataSource(selected);
        if (project == null || dataSource == null || project.isDisposed() || !project.isOpen()) {
            return;
        }
        MyBatisDatabaseSqlExecutionDialog dialog =
                new MyBatisDatabaseSqlExecutionDialog(project, selected.getName());
        if (!dialog.showAndGet()) {
            return;
        }
        MyBatisSqlExecutionPreparation preparation =
                MyBatisSqlExecutionPolicy.prepare(dialog.sql(), dialog.parameterPanel());
        if (preparation instanceof MyBatisSqlExecutionPreparation.Rejected rejected) {
            Messages.showErrorDialog(project, rejected.message(), "SQL 未通过执行校验");
            return;
        }
        MyBatisSqlExecutionPlan plan =
                ((MyBatisSqlExecutionPreparation.Ready) preparation).plan();
        MyBatisSqlExecutionAuthorization authorization = authorize(project, plan);
        if (!(authorization instanceof MyBatisSqlExecutionAuthorization.Authorized allowed)) {
            return;
        }
        try {
            MyBatisSqlExecutionResult result = ProgressManager.getInstance()
                    .runProcessWithProgressSynchronously(
                            () -> execute(project, dataSource, allowed.execution()),
                            "执行受控 SQL",
                            true,
                            project);
            new MyBatisDatabaseSqlResultDialog(project, plan.sql(), result).show();
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        }
    }

    private static @NotNull MyBatisSqlExecutionAuthorization authorize(
            @NotNull Project project,
            @NotNull MyBatisSqlExecutionPlan plan) {
        if (!plan.doubleConfirmationRequired()) {
            return MyBatisSqlExecutionPolicy.authorize(plan, false, "");
        }
        String riskMessage = "该语句属于 " + plan.riskAssessment().risk()
                + "，将尝试在独立事务中执行。部分数据库 DDL 可能自动提交，无法回滚。是否继续？";
        boolean riskConfirmed = Messages.showYesNoDialog(
                project,
                riskMessage,
                "危险 SQL 第一次确认",
                "继续确认",
                "取消",
                Messages.getWarningIcon()) == Messages.YES;
        if (!riskConfirmed) {
            return MyBatisSqlExecutionPolicy.authorize(plan, false, "");
        }
        String typed = Messages.showInputDialog(
                project,
                "请输入“" + MyBatisSqlExecutionPolicy.DANGEROUS_CONFIRMATION_PHRASE
                        + "”完成第二次确认：",
                "危险 SQL 第二次确认",
                Messages.getWarningIcon());
        return MyBatisSqlExecutionPolicy.authorize(
                plan, true, typed == null ? "" : typed);
    }

    private static @NotNull MyBatisSqlExecutionResult execute(
            @NotNull Project project,
            @NotNull LocalDataSource dataSource,
            @NotNull MyBatisAuthorizedSqlExecution execution) {
        var indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator == null) {
            return new MyBatisSqlExecutionResult.Failure("缺少可取消进度上下文", "", 0);
        }
        return new DatabaseToolsSqlExecutionBackend(project, dataSource)
                .execute(execution, indicator);
    }

    private static DbDataSource selectedDataSource(@NotNull AnActionEvent event) {
        DbElement[] elements = event.getData(DatabaseView.DB_ELEMENTS);
        if (elements == null || elements.length == 0) {
            return null;
        }
        DbDataSource first = elements[0].getDataSource();
        return Arrays.stream(elements)
                .allMatch(element -> first.equals(element.getDataSource())) ? first : null;
    }

    private static LocalDataSource localDataSource(@NotNull DbDataSource dataSource) {
        RawDataSource delegate = dataSource.getDelegateDataSource();
        return delegate instanceof LocalDataSource local ? local : null;
    }
}
