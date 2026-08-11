package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandQuickFix;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.SmartPsiElementPointer;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 从未匹配的 XML statement 定位到已解析的 Mapper 接口。
 */
final class LocateMapperInterfaceQuickFix extends ModCommandQuickFix {
    private final SmartPsiElementPointer<PsiClass> mapperPointer;
    private final String qualifiedName;

    LocateMapperInterfaceQuickFix(
            @NotNull SmartPsiElementPointer<PsiClass> mapperPointer,
            @NotNull String qualifiedName) {
        this.mapperPointer = mapperPointer;
        this.qualifiedName = qualifiedName;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MyBatisAssistantBundle.message("quickfix.locate.mapper.family");
    }

    @Override
    public @NotNull String getName() {
        return MyBatisAssistantBundle.message(
                "quickfix.locate.mapper.name",
                qualifiedName);
    }

    @Override
    public @NotNull ModCommand perform(
            @NotNull Project project,
            @NotNull ProblemDescriptor descriptor) {
        ProgressManager.checkCanceled();
        PsiClass mapper = mapperPointer.getElement();
        if (mapper == null
                || !mapper.isValid()
                || project.isDisposed()
                || !project.isOpen()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.locate.mapper.target.invalid"));
        }
        PsiIdentifier identifier = mapper.getNameIdentifier();
        return identifier == null || !identifier.isValid()
                ? ModCommand.error(MyBatisAssistantBundle.message(
                        "quickfix.locate.mapper.target.invalid"))
                : ModCommand.select(identifier);
    }
}
