package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.kotlin.MyBatisKotlinLightMethodResolver;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtVisitorVoid;

/**
 * 检查 Kotlin Mapper 在已有 XML 中缺失的 statement。
 */
public final class MyBatisMissingKotlinStatementInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new KtVisitorVoid() {
            @Override
            public void visitNamedFunction(@NotNull KtNamedFunction function) {
                ProgressManager.checkCanceled();
                PsiMethod method = MyBatisKotlinLightMethodResolver
                        .findSingle(function)
                        .orElse(null);
                if (method == null) {
                    return;
                }
                MyBatisStatementResolution resolution = MyBatisStatementResolver.resolve(method);
                if (!(resolution instanceof MyBatisStatementResolution.StatementMissing missing)) {
                    return;
                }
                PsiElement nameIdentifier = function.getNameIdentifier();
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
