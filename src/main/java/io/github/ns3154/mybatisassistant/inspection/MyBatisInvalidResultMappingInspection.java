package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.reference.MyBatisResultPropertyReference;
import io.github.ns3154.mybatisassistant.reference.MyBatisTypeReference;
import org.jetbrains.annotations.NotNull;

/**
 * 检查静态可证明不存在的 ResultMap 可写属性和 MyBatis 类型。
 */
public final class MyBatisInvalidResultMappingInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue value) {
                ProgressManager.checkCanceled();
                for (PsiReference reference : value.getReferences()) {
                    ProgressManager.checkCanceled();
                    if (reference instanceof MyBatisResultPropertyReference propertyReference
                            && propertyReference.isDefinitelyMissing()) {
                        holder.registerProblem(
                                value,
                                propertyReference.getRangeInElement(),
                                MyBatisAssistantBundle.message(
                                        "inspection.invalid.result.mapping.property.problem",
                                        propertyReference.propertyPath()));
                    } else if (reference instanceof MyBatisTypeReference typeReference
                            && typeReference.isDefinitelyMissing()) {
                        holder.registerProblem(
                                value,
                                typeReference.getRangeInElement(),
                                MyBatisAssistantBundle.message(
                                        "inspection.invalid.result.mapping.type.problem",
                                        value.getValue().trim()));
                    }
                }
            }
        };
    }
}
