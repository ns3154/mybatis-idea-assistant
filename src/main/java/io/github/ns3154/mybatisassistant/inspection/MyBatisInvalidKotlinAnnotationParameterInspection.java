package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiReference;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.reference.MyBatisAnnotationParameterReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jetbrains.kotlin.psi.KtVisitorVoid;

/**
 * 检查 Kotlin 直接 SQL 注解中可证明不存在的参数路径。
 */
public final class MyBatisInvalidKotlinAnnotationParameterInspection
        extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new KtVisitorVoid() {
            @Override
            public void visitStringTemplateExpression(
                    @NotNull KtStringTemplateExpression expression) {
                ProgressManager.checkCanceled();
                for (PsiReference reference : expression.getReferences()) {
                    ProgressManager.checkCanceled();
                    if (reference instanceof MyBatisAnnotationParameterReference parameterReference
                            && parameterReference.isDefinitelyMissing()) {
                        holder.registerProblem(
                                expression,
                                parameterReference.getRangeInElement(),
                                MyBatisAssistantBundle.message(
                                        "inspection.invalid.annotation.parameter.problem",
                                        parameterReference.parameterPath()));
                    }
                }
            }
        };
    }
}
