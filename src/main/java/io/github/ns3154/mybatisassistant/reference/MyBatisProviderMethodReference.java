package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import io.github.ns3154.mybatisassistant.resolve.MyBatisProviderMethodResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class MyBatisProviderMethodReference extends PsiPolyVariantReferenceBase<PsiLiteralExpression> {
    private final PsiAnnotation providerAnnotation;

    MyBatisProviderMethodReference(
            @NotNull PsiLiteralExpression element,
            @NotNull PsiAnnotation providerAnnotation) {
        super(element, new TextRange(1, Math.max(1, element.getTextLength() - 1)), false);
        this.providerAnnotation = providerAnnotation;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        if (!getElement().isValid() || !providerAnnotation.isValid()) {
            return ResolveResult.EMPTY_ARRAY;
        }
        Project project = getElement().getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return ResolveResult.EMPTY_ARRAY;
        }
        List<ResolveResult> results = new ArrayList<>();
        for (PsiMethod method : MyBatisProviderMethodResolver.find(providerAnnotation)) {
            ProgressManager.checkCanceled();
            results.add(new PsiElementResolveResult(method));
        }
        return results.toArray(ResolveResult.EMPTY_ARRAY);
    }
}
