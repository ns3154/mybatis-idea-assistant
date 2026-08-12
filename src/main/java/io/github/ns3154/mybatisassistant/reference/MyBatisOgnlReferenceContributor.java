package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlLanguage;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlOccurrence;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlPsiSupport;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlSemanticModel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 从已缓存的 OGNL 语义模型生成平台原生引用。
 */
public final class MyBatisOgnlReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(),
                new Provider());
    }

    private static final class Provider extends PsiReferenceProvider {
        @Override
        public @NotNull PsiReference[] getReferencesByElement(
                @NotNull PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            PsiFile file = element.getContainingFile();
            if (file == null || file.getLanguage() != MyBatisOgnlLanguage.INSTANCE) {
                return PsiReference.EMPTY_ARRAY;
            }
            MyBatisOgnlSemanticModel model = MyBatisOgnlPsiSupport.semanticModel(file);
            if (model == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            List<PsiReference> references = new ArrayList<>();
            for (MyBatisOgnlOccurrence occurrence : model.occurrences()) {
                ProgressManager.checkCanceled();
                if (MyBatisOgnlPsiSupport.occurrenceHost(file, occurrence.range()) != element) {
                    continue;
                }
                references.add(new MyBatisOgnlReference(
                        element,
                        MyBatisOgnlPsiSupport.rangeInElement(element, occurrence.range()),
                        occurrence));
            }
            return references.toArray(PsiReference.EMPTY_ARRAY);
        }
    }
}
