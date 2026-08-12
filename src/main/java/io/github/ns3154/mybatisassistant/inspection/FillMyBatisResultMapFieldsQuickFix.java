package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisResultMapMappingPlanner;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisResultMapSchemaResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 在平台单一写命令中补齐简单 ResultMap 的未映射字段，
 * 并在落盘期间锁定元数据世代。
 */
final class FillMyBatisResultMapFieldsQuickFix implements LocalQuickFix {
    private final String namespace;
    private final String resultMapId;
    private final String targetFileUrl;
    private final String expectedSourceText;
    @FileModifier.SafeFieldForPreview
    private final MyBatisResultMapMappingPlanner.Plan expectedPlan;
    private final long expectedMetadataGeneration;

    FillMyBatisResultMapFieldsQuickFix(
            @NotNull String namespace,
            @NotNull String resultMapId,
            @NotNull String targetFileUrl,
            @NotNull String expectedSourceText,
            @NotNull MyBatisResultMapMappingPlanner.Plan expectedPlan,
            long expectedMetadataGeneration) {
        this.namespace = namespace;
        this.resultMapId = resultMapId;
        this.targetFileUrl = targetFileUrl;
        this.expectedSourceText = expectedSourceText;
        this.expectedPlan = expectedPlan;
        this.expectedMetadataGeneration = expectedMetadataGeneration;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MyBatisAssistantBundle.message("quickfix.resultmap.fill.family");
    }

    @Override
    public @NotNull String getName() {
        return MyBatisAssistantBundle.message(
                "quickfix.resultmap.fill.name",
                expectedPlan.tableIdentity().table().name(),
                expectedPlan.entries().size());
    }

    @Override
    public void applyFix(
            @NotNull Project project,
            @NotNull ProblemDescriptor descriptor) {
        ProgressManager.checkCanceled();
        XmlTag resultMap = descriptorTarget(descriptor);
        if (!validTarget(project, resultMap, false)) {
            return;
        }
        MyBatisDatabaseMetadataService metadata =
                MyBatisDatabaseMetadataService.getInstance(project);
        metadata.withCurrentVersion(expectedMetadataGeneration, loaded -> {
            ProgressManager.checkCanceled();
            if (!validTarget(project, resultMap, false)) {
                return false;
            }
            var resolved = MyBatisResultMapSchemaResolver.resolve(
                    resultMap,
                    loaded.snapshots());
            if (resolved.isEmpty()) {
                return false;
            }
            var plan = MyBatisResultMapMappingPlanner.plan(
                    resultMap,
                    resolved.orElseThrow());
            if (plan.isEmpty() || !expectedPlan.equals(plan.orElseThrow())) {
                return false;
            }
            replaceWithPlan(project, resultMap);
            return true;
        });
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor) {
        XmlTag resultMap = descriptorTarget(previewDescriptor);
        if (resultMap == null
                || !IntentionPreviewUtils.isPreviewElement(resultMap)
                || !validTarget(project, resultMap, true)) {
            return IntentionPreviewInfo.EMPTY;
        }
        replaceWithPlan(project, resultMap);
        return IntentionPreviewInfo.DIFF;
    }

    private void replaceWithPlan(
            @NotNull Project project,
            @NotNull XmlTag resultMap) {
        replaceWithPlan(project, resultMap, ProgressManager::checkCanceled);
    }

    void replaceWithPlan(
            @NotNull Project project,
            @NotNull XmlTag resultMap,
            @NotNull Runnable cancellationCheckpoint) {
        XmlTag replacement = XmlElementFactory.getInstance(project)
                .createTagFromText(resultMap.getText());
        List<MyBatisResultMapMappingPlanner.Entry> primaryKeys = expectedPlan.entries().stream()
                .filter(MyBatisResultMapMappingPlanner.Entry::primaryKey)
                .toList();
        for (int index = primaryKeys.size() - 1; index >= 0; index--) {
            cancellationCheckpoint.run();
            addMapping(project, replacement, primaryKeys.get(index), true);
        }
        for (MyBatisResultMapMappingPlanner.Entry entry : expectedPlan.entries()) {
            cancellationCheckpoint.run();
            if (!entry.primaryKey()) {
                addMapping(project, replacement, entry, false);
            }
        }
        cancellationCheckpoint.run();
        resultMap.replace(replacement);
    }

    private boolean validTarget(
            @NotNull Project project,
            @Nullable XmlTag resultMap,
            boolean preview) {
        return resultMap != null
                && resultMap.isValid()
                && !project.isDisposed()
                && project.isOpen()
                && validWritableTarget(resultMap)
                && expectedSourceText.equals(resultMap.getText())
                && validFile(resultMap.getContainingFile(), preview);
    }

    private boolean validWritableTarget(@NotNull XmlTag resultMap) {
        XmlTag mapper = resultMap.getParentTag();
        return "resultMap".equals(resultMap.getName())
                && resultMapId.equals(resultMap.getAttributeValue("id"))
                && mapper != null
                && MyBatisXmlModel.isMapperRoot(mapper)
                && namespace.equals(MyBatisXmlModel.namespace(mapper));
    }

    private boolean validFile(@Nullable PsiFile file, boolean preview) {
        if (file == null) {
            return false;
        }
        if (preview) {
            return true;
        }
        return file.getVirtualFile() != null
                && targetFileUrl.equals(file.getVirtualFile().getUrl())
                && file.isWritable();
    }

    private static @Nullable XmlTag descriptorTarget(
            @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element instanceof XmlTag tag) {
            return tag;
        }
        return element == null
                ? null
                : PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
    }

    private static @NotNull XmlTag addMapping(
            @NotNull Project project,
            @NotNull XmlTag resultMap,
            @NotNull MyBatisResultMapMappingPlanner.Entry entry,
            boolean first) {
        String tagName = entry.primaryKey() ? "id" : "result";
        XmlTag template = XmlElementFactory.getInstance(project).createTagFromText(
                "<" + tagName
                        + " column=\"" + StringUtil.escapeXmlEntities(entry.column())
                        + "\" property=\"" + StringUtil.escapeXmlEntities(entry.property())
                        + "\"/>");
        XmlTag added = resultMap.addSubTag(template, first);
        return (XmlTag) CodeStyleManager.getInstance(project).reformat(added);
    }
}
