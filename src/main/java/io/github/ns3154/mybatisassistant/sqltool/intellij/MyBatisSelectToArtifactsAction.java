package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisSelectArtifactConversionResult;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisSelectArtifactConverter;
import org.jetbrains.annotations.NotNull;

/**
 * 从 Tools 菜单打开保守 SELECT 产物转换器。
 */
public final class MyBatisSelectToArtifactsAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.SelectToArtifacts";

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
            new MyBatisSelectToArtifactsDialog(project).show();
        }
    }

    static @NotNull String render(
            String sql,
            String basePackage,
            String mapperName,
            String methodName) {
        MyBatisSelectArtifactConversionResult result = MyBatisSelectArtifactConverter.convert(
                sql, basePackage, mapperName, methodName);
        if (result instanceof MyBatisSelectArtifactConversionResult.Failure failure) {
            return MyBatisAssistantBundle.message(
                    "sqltool.select.conversion.failure", failure.offset(), failure.message());
        }
        MyBatisSelectArtifactConversionResult.Success success =
                (MyBatisSelectArtifactConversionResult.Success) result;
        return MyBatisAssistantBundle.message("sqltool.select.conversion.preview") + '\n'
                + success.warnings().stream().map(warning -> "- " + warning)
                        .reduce((left, right) -> left + "\n" + right).orElse("")
                + "\n\n===== Mapper.java =====\n" + success.mapperSource()
                + "\n===== Mapper.xml =====\n" + success.xmlSource()
                + "\n===== Row.java =====\n" + success.rowModelSource();
    }
}
