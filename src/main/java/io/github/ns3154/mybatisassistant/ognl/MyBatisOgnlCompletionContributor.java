package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;

/**
 * 从已缓存语义 occurrence 提供根变量、属性、方法和静态成员补全。
 */
public final class MyBatisOgnlCompletionContributor extends CompletionContributor {
    public MyBatisOgnlCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(MyBatisOgnlLanguage.INSTANCE),
                new Provider());
    }

    private static final class Provider extends CompletionProvider<CompletionParameters> {
        @Override
        protected void addCompletions(
                @NotNull CompletionParameters parameters,
                @NotNull ProcessingContext context,
                @NotNull CompletionResultSet result) {
            ProgressManager.checkCanceled();
            PsiFile file = parameters.getPosition().getContainingFile();
            if (file.getLanguage() != MyBatisOgnlLanguage.INSTANCE) {
                return;
            }
            for (String variant : completionVariants(file, parameters.getOffset())) {
                ProgressManager.checkCanceled();
                result.addElement(LookupElementBuilder.create(variant));
            }
        }
    }

    static @NotNull List<String> completionVariants(
            @NotNull PsiFile file,
            int offset) {
        ProgressManager.checkCanceled();
        MyBatisOgnlSemanticModel model = MyBatisOgnlPsiSupport.semanticModel(file);
        if (model == null) {
            return List.of();
        }
        MyBatisOgnlOccurrence occurrence = closestOccurrence(model, offset);
        return occurrence == null ? List.of() : occurrence.result().variants();
    }

    private static MyBatisOgnlOccurrence closestOccurrence(
            @NotNull MyBatisOgnlSemanticModel model,
            int offset) {
        List<MyBatisOgnlOccurrence> containing = model.occurrences().stream()
                .filter(occurrence -> occurrence.range().startOffset() <= offset
                        && occurrence.range().endOffset() >= offset)
                .sorted(Comparator.comparingInt(occurrence ->
                        occurrence.range().length()))
                .toList();
        if (!containing.isEmpty()) {
            return containing.getFirst();
        }
        return model.occurrences().stream()
                .filter(occurrence -> occurrence.range().endOffset() <= offset)
                .max(Comparator.comparingInt(occurrence ->
                        occurrence.range().endOffset()))
                .orElse(null);
    }
}
