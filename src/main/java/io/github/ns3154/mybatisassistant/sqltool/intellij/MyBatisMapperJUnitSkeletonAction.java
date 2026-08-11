package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisJUnitPlatform;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestGeneration;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestRequestFactory;
import io.github.ns3154.mybatisassistant.sqltool.testgen.MyBatisMapperTestSkeletonGenerator;
import org.jetbrains.annotations.NotNull;

/**
 * 从当前 Mapper 方法生成只读 JUnit 骨架预览。
 */
public final class MyBatisMapperJUnitSkeletonAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.MapperJUnitSkeleton";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiMethod method = selectedMethod(event);
        event.getPresentation().setEnabledAndVisible(
                project != null && project.isOpen() && !project.isDisposed()
                        && method != null
                        && MyBatisMapperTestRequestFactory.create(
                                method, MyBatisJUnitPlatform.JUNIT_5)
                                instanceof MyBatisMapperTestRequestFactory.Result.Success);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiMethod method = selectedMethod(event);
        if (project == null || method == null) {
            return;
        }
        MyBatisJUnitPlatformDialog platformDialog = new MyBatisJUnitPlatformDialog(project);
        if (!platformDialog.showAndGet()) {
            return;
        }
        MyBatisJUnitPlatform platform = platformDialog.selectedPlatform();
        MyBatisMapperTestRequestFactory.Result extracted = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                        () -> ReadAction.computeCancellable(
                                () -> MyBatisMapperTestRequestFactory.create(method, platform)),
                        "解析 Mapper 方法签名",
                        true,
                        project);
        if (extracted instanceof MyBatisMapperTestRequestFactory.Result.Failure failure) {
            Messages.showErrorDialog(project, failure.message(), "无法生成 JUnit 骨架");
            return;
        }
        var request = ((MyBatisMapperTestRequestFactory.Result.Success) extracted).request();
        MyBatisMapperTestGeneration generation =
                MyBatisMapperTestSkeletonGenerator.generate(request);
        new MyBatisSqlToolPreviewDialog(
                project,
                "预览 " + generation.suggestedFileName(),
                "仅生成预览，不会写文件或连接数据库。\n\n" + generation.source())
                .show();
    }

    static PsiMethod selectedMethod(AnActionEvent event) {
        PsiElement element = event.getData(CommonDataKeys.PSI_ELEMENT);
        if (element == null) {
            PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            if (file != null && editor != null) {
                element = file.findElementAt(editor.getCaretModel().getOffset());
            }
        }
        return element == null ? null
                : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }
}
