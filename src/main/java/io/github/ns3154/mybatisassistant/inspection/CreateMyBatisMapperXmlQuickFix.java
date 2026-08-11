package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandQuickFix;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.codeStyle.CodeStyleManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

final class CreateMyBatisMapperXmlQuickFix extends ModCommandQuickFix {
    private static final String MAPPER_DIRECTORY = "mapper";
    private final SmartPsiElementPointer<PsiDirectory> resourceRootPointer;
    private final String namespace;
    private final String fileName;
    private final String targetName;

    CreateMyBatisMapperXmlQuickFix(
            @NotNull SmartPsiElementPointer<PsiDirectory> resourceRootPointer,
            @NotNull String namespace,
            @NotNull String fileName,
            @NotNull String targetName) {
        this.resourceRootPointer = resourceRootPointer;
        this.namespace = namespace;
        this.fileName = fileName;
        this.targetName = targetName;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MyBatisAssistantBundle.message("quickfix.create.mapper.xml.family");
    }

    @Override
    public @NotNull String getName() {
        return MyBatisAssistantBundle.message(
                "quickfix.create.mapper.xml.name",
                targetName);
    }

    @Override
    public @NotNull ModCommand perform(
            @NotNull Project project,
            @NotNull ProblemDescriptor descriptor) {
        ProgressManager.checkCanceled();
        PsiDirectory resourceRoot = resourceRootPointer.getElement();
        if (resourceRoot == null
                || !resourceRoot.isValid()
                || project.isDisposed()
                || !project.isOpen()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.create.mapper.xml.target.invalid"));
        }
        VirtualFile virtualFile = resourceRoot.getVirtualFile();
        if (!virtualFile.isWritable()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.create.mapper.xml.target.readonly",
                    targetName));
        }
        PsiElement source = descriptor.getPsiElement();
        if (source == null || !source.isValid()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.create.mapper.xml.target.invalid"));
        }
        return ModCommand.psiUpdate(source, (ignored, updater) -> {
            ProgressManager.checkCanceled();
            PsiDirectory writableRoot = updater.getWritable(resourceRoot);
            PsiDirectory mapperDirectory = writableRoot.findSubdirectory(MAPPER_DIRECTORY);
            if (mapperDirectory == null) {
                mapperDirectory = writableRoot.createSubdirectory(MAPPER_DIRECTORY);
            }
            if (mapperDirectory.findFile(fileName) != null) {
                updater.cancel(MyBatisAssistantBundle.message(
                        "quickfix.create.mapper.xml.conflict",
                        targetName));
                return;
            }
            PsiFile template = PsiFileFactory.getInstance(project).createFileFromText(
                    fileName,
                    XMLLanguage.INSTANCE,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<mapper namespace=\"" + namespace + "\">\n"
                            + "    <!-- TODO: 添加 statement -->\n"
                            + "</mapper>\n");
            PsiElement added = mapperDirectory.add(template);
            CodeStyleManager.getInstance(project).reformat(added);
        });
    }
}
