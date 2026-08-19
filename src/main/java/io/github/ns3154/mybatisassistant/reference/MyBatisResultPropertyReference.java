package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import org.jetbrains.annotations.NotNull;

public final class MyBatisResultPropertyReference extends PsiPolyVariantReferenceBase<PsiElement> {
    private final MyBatisParameterExpressionParser.ParameterPath path;
    private final int segmentIndex;

    MyBatisResultPropertyReference(
            @NotNull PsiElement element,
            @NotNull TextRange range,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        super(element, range, true);
        this.path = path;
        this.segmentIndex = segmentIndex;
    }

    @Override
    public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        MyBatisParameterPathResolution resolution = resolution();
        return resolution.targets().stream()
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        ResolveResult[] results = multiResolve(false);
        return results.length == 1
                && element.getManager().areElementsEquivalent(
                element,
                results[0].getElement());
    }

    @Override
    public @NotNull Object[] getVariants() {
        return resolution().variants().stream()
                .map(LookupElementBuilder::create)
                .toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        return MyBatisReferenceRenameSupport.renameRange(
                this,
                MyBatisReferenceRenameSupport.propertyName(this, newElementName));
    }

    public boolean isDefinitelyMissing() {
        return resolution().status() == MyBatisParameterPathResolution.Status.DEFINITE_MISSING;
    }

    public @NotNull String propertyPath() {
        return path.segments().subList(0, segmentIndex + 1).stream()
                .map(MyBatisParameterExpressionParser.PathSegment::name)
                .reduce((left, right) -> left + '.' + right)
                .orElse("");
    }

    private @NotNull MyBatisParameterPathResolution resolution() {
        return MyBatisResultPropertyPathResolver.resolve(getElement(), path, segmentIndex);
    }
}
