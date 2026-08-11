package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sqltool.format.MyBatisXmlFormatResult;
import io.github.ns3154.mybatisassistant.sqltool.format.MyBatisXmlFormatter;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 对当前 Mapper XML 提供全量预览、执行前复核和单命令格式化。
 */
public final class MyBatisXmlFormatAction extends AnAction {
    public static final String ID = "MyBatisAssistant.SqlTool.FormatMapperXml";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        event.getPresentation().setEnabledAndVisible(
                project != null && project.isOpen() && !project.isDisposed()
                        && file instanceof XmlFile xmlFile && isMapper(xmlFile));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || !(file instanceof XmlFile xmlFile) || !isMapper(xmlFile)) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(xmlFile);
        if (document == null) {
            Messages.showErrorDialog(project, "无法读取当前 XML 文档", "格式化未执行");
            return;
        }
        String original = document.getText();
        MyBatisXmlFormatResult result = ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                        () -> MyBatisXmlFormatter.format(original, 2),
                        "分析 MyBatis XML 格式",
                        true,
                        project);
        if (result instanceof MyBatisXmlFormatResult.Failure failure) {
            Messages.showErrorDialog(project, failure.message(), "格式化未执行");
            return;
        }
        String formatted = ((MyBatisXmlFormatResult.Success) result).text();
        if (formatted.equals(original)) {
            Messages.showInfoMessage(project, "当前 MyBatis XML 已符合格式。", "无需格式化");
            return;
        }
        if (!new MyBatisXmlFormatPreviewDialog(project, original, formatted).showAndGet()) {
            return;
        }
        ApplyResult applied = apply(project, xmlFile, original, formatted);
        if (applied != ApplyResult.APPLIED) {
            Messages.showErrorDialog(project, applied.message, "格式化未执行");
        }
    }

    static @NotNull ApplyResult apply(
            @NotNull Project project,
            @NotNull XmlFile file,
            @NotNull String expectedText,
            @NotNull String formattedText) {
        if (!file.isValid() || file.getVirtualFile() == null) {
            return ApplyResult.INVALID_FILE;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(file);
        if (document == null) {
            return ApplyResult.INVALID_FILE;
        }
        if (expectedText.equals(formattedText)) {
            return ApplyResult.UNCHANGED;
        }
        AtomicReference<ApplyResult> result = new AtomicReference<>(ApplyResult.APPLIED);
        WriteCommandAction.writeCommandAction(project, file)
                .withName("格式化 MyBatis XML")
                .run(() -> {
                    if (!file.isValid() || file.getVirtualFile() == null) {
                        result.set(ApplyResult.INVALID_FILE);
                    } else if (!file.getVirtualFile().isWritable()) {
                        result.set(ApplyResult.READ_ONLY);
                    } else if (!document.getText().equals(expectedText)) {
                        result.set(ApplyResult.SOURCE_CHANGED);
                    } else {
                        document.setText(formattedText);
                        PsiDocumentManager.getInstance(project).commitDocument(document);
                    }
                });
        return result.get();
    }

    private static boolean isMapper(XmlFile xmlFile) {
        XmlTag rootTag = xmlFile.getRootTag();
        return rootTag != null && MyBatisXmlModel.isMapperRoot(rootTag);
    }

    enum ApplyResult {
        APPLIED(""),
        UNCHANGED("格式化结果与当前文档一致"),
        SOURCE_CHANGED("预览后文档已变化，请重新运行格式化"),
        READ_ONLY("当前 XML 文件只读"),
        INVALID_FILE("当前 XML 文件或文档已失效");

        private final String message;

        ApplyResult(String message) {
            this.message = message;
        }
    }
}
