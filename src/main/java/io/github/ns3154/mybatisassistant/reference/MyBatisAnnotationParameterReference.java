package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import org.jetbrains.annotations.NotNull;

public final class MyBatisAnnotationParameterReference
        extends PsiPolyVariantReferenceBase<PsiElement> {
    private final PsiMethod method;
    private final MyBatisParameterExpressionParser.ParameterPath path;
    private final int segmentIndex;

    MyBatisAnnotationParameterReference(
            @NotNull PsiElement literal,
            @NotNull TextRange range,
            @NotNull PsiMethod method,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        super(literal, range, true);
        this.method = method;
        this.path = path;
        this.segmentIndex = segmentIndex;
    }

    @Override
    public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        return resolution().targets().stream()
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        ResolveResult[] results = multiResolve(false);
        return results.length == 1
                && element.getManager().areElementsEquivalent(element, results[0].getElement());
    }

    @Override
    public @NotNull Object[] getVariants() {
        ProgressManager.checkCanceled();
        return resolution().variants().stream()
                .map(LookupElementBuilder::create)
                .toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        PsiElement target = resolve();
        String replacement = target instanceof com.intellij.psi.PsiMethod
                || target instanceof com.intellij.psi.PsiField
                ? MyBatisReferenceRenameSupport.propertyName(this, newElementName)
                : newElementName;
        return MyBatisReferenceRenameSupport.renameRange(this, replacement);
    }

    public boolean isDefinitelyMissing() {
        return resolution().status() == MyBatisParameterPathResolution.Status.DEFINITE_MISSING;
    }

    public @NotNull String parameterPath() {
        return path.segments().subList(0, segmentIndex + 1).stream()
                .map(MyBatisParameterExpressionParser.PathSegment::name)
                .reduce((left, right) -> left + '.' + right)
                .orElse("");
    }

    private @NotNull MyBatisParameterPathResolution resolution() {
        return MyBatisParameterPathResolver.resolveAnnotation(
                getElement(),
                method,
                path,
                segmentIndex);
    }
}
