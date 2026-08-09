package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolver;
import org.jetbrains.annotations.NotNull;

/**
 * 检查已有 Mapper XML 中缺失的 statement。
 */
public final class MyBatisMissingStatementInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                ProgressManager.checkCanceled();
                MyBatisStatementResolution resolution = MyBatisStatementResolver.resolve(method);
                if (!(resolution instanceof MyBatisStatementResolution.StatementMissing missing)) {
                    return;
                }

                PsiIdentifier nameIdentifier = method.getNameIdentifier();
                if (nameIdentifier == null || !nameIdentifier.isValid()) {
                    return;
                }
                holder.registerProblem(
                        nameIdentifier,
                        MyBatisAssistantBundle.message(
                                "inspection.missing.statement.problem",
                                missing.namespace(),
                                missing.statementId()));
            }
        };
    }
}
