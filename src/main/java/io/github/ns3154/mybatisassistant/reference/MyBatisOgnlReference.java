package io.github.ns3154.mybatisassistant.reference;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlOccurrence;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlPsiSupport;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSemanticResult;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSemanticStatus;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSymbolKind;
import org.jetbrains.annotations.NotNull;

/**
 * 只把确定、稳定的 OGNL 名称解析到 Java 或 XML 声明。
 */
public final class MyBatisOgnlReference extends PsiPolyVariantReferenceBase<PsiElement> {
    private final MyBatisOgnlOccurrence occurrence;

    MyBatisOgnlReference(
            @NotNull PsiElement element,
            @NotNull TextRange range,
            @NotNull MyBatisOgnlOccurrence occurrence) {
        super(element, range, true);
        this.occurrence = occurrence;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        return occurrence.result().targets().stream()
                .filter(PsiElement::isValid)
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
    public Object @NotNull [] getVariants() {
        ProgressManager.checkCanceled();
        return occurrence.result().variants().stream()
                .map(LookupElementBuilder::create)
                .toArray();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) {
        PsiElement target = resolve();
        String replacement = newElementName;
        if (target instanceof PsiMethod || target instanceof PsiField) {
            replacement = MyBatisReferenceRenameSupport.propertyName(this, newElementName);
        } else if (target instanceof PsiClass psiClass
                && occurrence.kind() == MyBatisOgnlSymbolKind.STATIC_CLASS) {
            String qualifiedName = psiClass.getQualifiedName();
            int separator = qualifiedName == null ? -1 : qualifiedName.lastIndexOf('.');
            replacement = separator < 0
                    ? newElementName
                    : qualifiedName.substring(0, separator + 1) + newElementName;
        }
        PsiElement element = getElement();
        PsiFile containingFile = element.getContainingFile();
        PsiElement stableFallback = MyBatisOgnlPsiSupport.host(containingFile);
        SmartPsiElementPointer<PsiElement> fallbackPointer = SmartPointerManager
                .getInstance(element.getProject())
                .createSmartPsiElementPointer(
                        stableFallback == null ? containingFile : stableFallback);
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(
                element.getProject());
        Document document = documentManager.getDocument(containingFile);
        if (document == null) {
            return MyBatisReferenceRenameSupport.renameRange(this, replacement);
        }
        TextRange range = getRangeInElement().shiftRight(element.getTextRange().getStartOffset());
        document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        documentManager.commitDocument(document);
        PsiFile updatedFile = documentManager.getPsiFile(document);
        if (updatedFile == null || !updatedFile.isValid()) {
            PsiElement fallback = fallbackPointer.getElement();
            if (fallback == null) {
                throw new com.intellij.util.IncorrectOperationException(
                        MyBatisAssistantBundle.message(
                                "reference.error.ognl.rename.psi.invalid"));
            }
            return fallback;
        }
        PsiElement updated = updatedFile.findElementAt(
                Math.min(range.getStartOffset(), Math.max(0, updatedFile.getTextLength() - 1)));
        return updated == null ? updatedFile : updated;
    }

    public boolean isDefinitelyMissing() {
        return occurrence.result().status()
                == MyBatisOgnlSemanticStatus.DEFINITE_MISSING;
    }

    public @NotNull MyBatisOgnlOccurrence occurrence() {
        return occurrence;
    }

    @NotNull MyBatisOgnlSemanticResult semanticResult() {
        return occurrence.result();
    }
}
