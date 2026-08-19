package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 从 Tools 菜单打开本地日志 SQL 还原工具，不读取项目文件或连接数据库。
 */
public final class MyBatisLogSqlRestoreAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.RestoreLogSql";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(
                project != null && project.isOpen() && !project.isDisposed());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || !project.isOpen() || project.isDisposed()) {
            return;
        }
        new MyBatisLogSqlRestoreDialog(project).show();
    }
}
