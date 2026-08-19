package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查未找到对应 Java Mapper 方法的 statement。该检查默认关闭。
 */
public final class MyBatisUnusedStatementInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                if (!MyBatisXmlInspectionSupport.isDirectStatement(tag)
                        || !MyBatisXmlInspectionSupport.isReady(tag)) {
                    return;
                }
                XmlTag mapper = tag.getParentTag();
                String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
                String statementId = MyBatisXmlModel.statementId(tag);
                XmlAttributeValue idValue = MyBatisXmlInspectionSupport.exactAttributeValue(
                        tag,
                        "id");
                if (namespace == null
                        || statementId == null
                        || idValue == null
                        || !MyBatisXmlInspectionSupport.isStaticValue(namespace)
                        || !MyBatisXmlInspectionSupport.isStaticValue(statementId)) {
                    return;
                }
                try {
                    List<PsiClass> mappers = MyBatisXmlInspectionSupport
                            .findExactMapperInterfaces(tag, namespace);
                    if (!mappers.isEmpty()
                            && MyBatisMapperMethodResolver.find(
                                    tag,
                                    namespace,
                                    statementId).isEmpty()) {
                        holder.registerProblem(
                                idValue,
                                MyBatisAssistantBundle.message(
                                        "inspection.unused.statement.problem",
                                        namespace,
                                        statementId),
                                navigationFixes(mappers, namespace));
                    }
                } catch (IndexNotReadyException ignored) {
                    // 索引不可用时不把暂时无结果当成未使用。
                }
            }
        };
    }

    private static @NotNull LocalQuickFix[] navigationFixes(
            @NotNull List<PsiClass> mappers,
            @NotNull String namespace) {
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(
                mappers.getFirst().getProject());
        List<LocalQuickFix> fixes = new ArrayList<>();
        for (PsiClass mapper : mappers) {
            ProgressManager.checkCanceled();
            fixes.add(new LocateMapperInterfaceQuickFix(
                    pointerManager.createSmartPsiElementPointer(mapper),
                    namespace));
        }
        return fixes.toArray(LocalQuickFix.EMPTY_ARRAY);
    }
}
