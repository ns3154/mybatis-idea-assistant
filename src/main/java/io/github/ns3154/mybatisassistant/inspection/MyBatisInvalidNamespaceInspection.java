package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;

/**
 * 检查无法精确解析到 Java Mapper 接口的 namespace。
 */
public final class MyBatisInvalidNamespaceInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                if (!MyBatisXmlModel.isMapperRoot(tag)
                        || !MyBatisXmlInspectionSupport.isReady(tag)) {
                    return;
                }
                XmlAttributeValue value = MyBatisXmlInspectionSupport.exactAttributeValue(
                        tag,
                        "namespace");
                String namespace = value == null ? null : value.getValue().trim();
                if (value == null
                        || !MyBatisXmlInspectionSupport.isStaticValue(namespace)) {
                    return;
                }
                try {
                    if (!MyBatisXmlInspectionSupport.hasExactMapperInterface(tag, namespace)) {
                        holder.registerProblem(
                                value,
                                MyBatisAssistantBundle.message(
                                        "inspection.invalid.namespace.problem",
                                        namespace));
                    }
                } catch (IndexNotReadyException ignored) {
                    // Smart -> Dumb 竞态时不制造诊断。
                }
            }
        };
    }
}
