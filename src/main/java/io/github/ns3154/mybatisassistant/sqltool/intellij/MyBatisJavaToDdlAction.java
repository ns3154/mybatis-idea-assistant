package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisJavaDdlGeneration;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisJavaDdlGenerator;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisJavaTableExtractionResult;
import io.github.ns3154.mybatisassistant.sqltool.conversion.MyBatisJavaTableExtractor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 从当前 Java 实体类生成只读 DDL 预览。
 */
public final class MyBatisJavaToDdlAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.JavaToDdl";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(
                project != null && project.isOpen() && !project.isDisposed()
                        && selectedClass(event) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiClass source = selectedClass(event);
        if (project == null || source == null) {
            return;
        }
        MyBatisSqlDialectDialog dialectDialog = new MyBatisSqlDialectDialog(
                project, "Java 类生成 DDL");
        if (!dialectDialog.showAndGet()) {
            return;
        }
        Preview preview = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> ReadAction.computeCancellable(
                        () -> buildPreview(source, dialectDialog.selectedDialect())),
                "从 Java PSI 生成 DDL",
                true,
                project);
        if (preview.failure != null) {
            Messages.showErrorDialog(project, preview.failure, "DDL 生成失败");
            return;
        }
        new MyBatisSqlToolPreviewDialog(
                project, "预览 Java 类生成 DDL", preview.text).show();
    }

    static @NotNull Preview buildPreview(
            @NotNull PsiClass source,
            @NotNull MyBatisSqlDialect dialect) {
        MyBatisJavaTableExtractionResult extraction = MyBatisJavaTableExtractor.extract(source);
        if (extraction instanceof MyBatisJavaTableExtractionResult.Failure failure) {
            return new Preview("", failure.message());
        }
        MyBatisJavaTableExtractionResult.Success success =
                (MyBatisJavaTableExtractionResult.Success) extraction;
        try {
            MyBatisJavaDdlGeneration generation = MyBatisJavaDdlGenerator.generate(
                    success.table(), dialect);
            List<String> warnings = new ArrayList<>(success.warnings());
            warnings.addAll(generation.warnings());
            return new Preview(render(generation.ddl(), warnings), null);
        } catch (IllegalArgumentException invalid) {
            return new Preview("", invalid.getMessage());
        }
    }

    private static String render(String ddl, List<String> warnings) {
        if (warnings.isEmpty()) {
            return "风险：仅生成预览，不会自动执行。\n\n" + ddl;
        }
        return "风险：存在需要确认的类型、注释或索引降级。\n"
                + warnings.stream().map(warning -> "- " + warning)
                        .reduce((left, right) -> left + "\n" + right).orElse("")
                + "\n\n" + ddl;
    }

    private static PsiClass selectedClass(AnActionEvent event) {
        PsiElement element = event.getData(CommonDataKeys.PSI_ELEMENT);
        PsiClass parent = element == null
                ? null : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (parent != null) {
            return parent;
        }
        return event.getData(CommonDataKeys.PSI_FILE) instanceof PsiJavaFile javaFile
                && javaFile.getClasses().length > 0 ? javaFile.getClasses()[0] : null;
    }

    record Preview(@NotNull String text, String failure) {
    }
}
