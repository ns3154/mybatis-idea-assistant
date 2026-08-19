package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

public final class MyBatisConstructorArgumentReference
        extends PsiPolyVariantReferenceBase<XmlAttributeValue> {
    MyBatisConstructorArgumentReference(@NotNull XmlAttributeValue element) {
        super(element, MyBatisReferenceSupport.valueRange(element), true);
    }

    @Override
    public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        return resolution().targets().stream()
                .filter(PsiParameter.class::isInstance)
                .map(PsiElementResolveResult::new)
                .toArray(ResolveResult[]::new);
    }

    @Override
    public @NotNull Object[] getVariants() {
        return resolution().variants().stream()
                .map(LookupElementBuilder::create)
                .toArray();
    }

    private @NotNull MyBatisParameterPathResolution resolution() {
        return MyBatisResultPropertyPathResolver.resolveConstructorArgument(
                getElement(),
                getElement().getValue().trim());
    }
}
