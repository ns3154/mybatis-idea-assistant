package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlLanguage;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlOccurrence;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlPsiSupport;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSemanticModel;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSemanticStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 只报告能够通过当前 Mapper 上下文静态证明不存在的 OGNL 名称。
 */
public final class MyBatisOgnlUnresolvedSymbolInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (file.getLanguage() != MyBatisOgnlLanguage.INSTANCE) {
                    return;
                }
                inspect(file, holder);
            }
        };
    }

    private static void inspect(
            @NotNull PsiFile file,
            @NotNull ProblemsHolder holder) {
        ProgressManager.checkCanceled();
        MyBatisOgnlSemanticModel model = MyBatisOgnlPsiSupport.semanticModel(file);
        if (model == null || !model.parseResult().diagnostics().isEmpty()) {
            return;
        }
        for (MyBatisOgnlOccurrence occurrence : model.occurrences()) {
            ProgressManager.checkCanceled();
            if (occurrence.result().status()
                    != MyBatisOgnlSemanticStatus.DEFINITE_MISSING) {
                continue;
            }
            PsiElement element = MyBatisOgnlPsiSupport.occurrenceHost(
                    file,
                    occurrence.range());
            holder.registerProblem(
                    element,
                    MyBatisOgnlPsiSupport.rangeInElement(element, occurrence.range()),
                    MyBatisAssistantBundle.message(
                            "inspection.ognl.unresolved.symbol.problem",
                            occurrence.name()));
        }
    }
}
