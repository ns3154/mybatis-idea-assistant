package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReference;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.reference.MyBatisAnnotationParameterReference;
import org.jetbrains.annotations.NotNull;

public final class MyBatisInvalidAnnotationParameterInspection
        extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitLiteralExpression(@NotNull PsiLiteralExpression expression) {
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
