package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlToken;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.reference.MyBatisParameterReference;
import org.jetbrains.annotations.NotNull;

/**
 * 只报告能够静态证明不存在的 Mapper 参数或嵌套属性。
 */
public final class MyBatisInvalidParameterPathInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue value) {
                inspectReferences(value, holder);
            }

            @Override
            public void visitXmlToken(@NotNull XmlToken token) {
                inspectReferences(token, holder);
            }
        };
    }

    private static void inspectReferences(
            @NotNull PsiElement element,
            @NotNull ProblemsHolder holder) {
        ProgressManager.checkCanceled();
        for (PsiReference reference : element.getReferences()) {
            ProgressManager.checkCanceled();
            if (reference instanceof MyBatisParameterReference parameterReference
                    && parameterReference.isDefinitelyMissing()) {
                holder.registerProblem(
                        element,
                        parameterReference.getRangeInElement(),
                        MyBatisAssistantBundle.message(
                                "inspection.invalid.parameter.path.problem",
                                parameterReference.parameterPath()));
            }
        }
    }
}
