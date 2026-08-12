package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGeneratedArtifact;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisSqlArtifactConversionResult;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisSqlArtifactConverter;
import org.jetbrains.annotations.NotNull;

/**
 * 打开 CREATE TABLE 到 MyBatis 产物的纯内存转换工具。
 */
public final class MyBatisDdlToArtifactsAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.DdlToArtifacts";

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
        if (project != null) {
            new MyBatisDdlToArtifactsDialog(project).show();
        }
    }

    static @NotNull String render(
            @NotNull String ddl,
            @NotNull MyBatisSqlDialect dialect,
            @NotNull String basePackage) {
        MyBatisSqlArtifactConversionResult result = MyBatisSqlArtifactConverter.convert(
                ddl, dialect, basePackage);
        if (result instanceof MyBatisSqlArtifactConversionResult.Failure failure) {
            return MyBatisAssistantBundle.message(
                    "sqltool.ddl.conversion.failure", failure.code(), failure.message());
        }
        MyBatisSqlArtifactConversionResult.Success success =
                (MyBatisSqlArtifactConversionResult.Success) result;
        StringBuilder text = new StringBuilder(
                MyBatisAssistantBundle.message("sqltool.ddl.conversion.preview") + '\n');
        if (!success.warnings().isEmpty()) {
            text.append(MyBatisAssistantBundle.message(
                    "sqltool.ddl.conversion.warnings")).append('\n');
            success.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
        }
        for (MyBatisGeneratedArtifact artifact : success.bundle().artifacts()) {
            text.append("\n===== ").append(artifact.relativePath()).append(" =====\n")
                    .append(artifact.content());
        }
        return text.toString().stripTrailing();
    }
}
