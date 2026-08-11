package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandQuickFix;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

final class CreateMyBatisStatementQuickFix extends ModCommandQuickFix {
    private static final Set<String> SUPPORTED_TAGS = Set.of(
            "select",
            "insert",
            "update",
            "delete");
    private final SmartPsiElementPointer<XmlTag> mapperPointer;
    private final String namespace;
    private final String statementId;
    private final String statementTag;
    private final String targetName;

    CreateMyBatisStatementQuickFix(
            @NotNull SmartPsiElementPointer<XmlTag> mapperPointer,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull String statementTag,
            @NotNull String targetName) {
        this.mapperPointer = mapperPointer;
        this.namespace = namespace;
        this.statementId = statementId;
        this.statementTag = statementTag;
        this.targetName = targetName;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MyBatisAssistantBundle.message("quickfix.create.statement.family");
    }

    @Override
    public @NotNull String getName() {
        return MyBatisAssistantBundle.message(
                "quickfix.create.statement.name",
                statementTag,
                targetName);
    }

    @Override
    public @NotNull ModCommand perform(
            @NotNull Project project,
            @NotNull ProblemDescriptor descriptor) {
        ProgressManager.checkCanceled();
        XmlTag mapper = mapperPointer.getElement();
        if (mapper == null || !mapper.isValid() || project.isDisposed() || !project.isOpen()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.create.statement.target.invalid"));
        }
        PsiFile file = mapper.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        if (virtualFile == null || !virtualFile.isWritable()) {
            return ModCommand.error(MyBatisAssistantBundle.message(
                    "quickfix.create.statement.target.readonly",
                    targetName));
        }
        return ModCommand.psiUpdate(mapper, (writableMapper, updater) -> {
            ProgressManager.checkCanceled();
            if (!MyBatisXmlModel.isMapperRoot(writableMapper)
                    || !namespace.equals(MyBatisXmlModel.namespace(writableMapper))
                    || !SUPPORTED_TAGS.contains(statementTag)) {
                updater.cancel(MyBatisAssistantBundle.message(
                        "quickfix.create.statement.target.changed"));
                return;
            }
            for (XmlTag child : writableMapper.getSubTags()) {
                ProgressManager.checkCanceled();
                if (MyBatisXmlModel.isStatement(child)
                        && statementId.equals(MyBatisXmlModel.statementId(child))) {
                    updater.cancel(MyBatisAssistantBundle.message(
                            "quickfix.create.statement.conflict",
                            namespace,
                            statementId));
                    return;
                }
            }
            XmlTag template = XmlElementFactory.getInstance(project).createTagFromText(
                    "<" + statementTag + " id=\"" + statementId + "\">\n"
                            + "    <!-- TODO: 补充 SQL -->\n"
                            + "</" + statementTag + ">");
            XmlTag added = writableMapper.addSubTag(template, false);
            CodeStyleManager.getInstance(project).reformat(added);
            updater.select(added);
            updater.moveCaretTo(added);
        });
    }
}
