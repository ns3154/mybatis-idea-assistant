package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.ContributedReferenceHost;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 注入 OGNL PSI、XML 宿主和纯语义范围之间的公共桥接。
 */
public final class MyBatisOgnlPsiSupport {
    private MyBatisOgnlPsiSupport() {
    }

    public static @Nullable XmlAttributeValue host(@NotNull PsiFile injectedFile) {
        PsiLanguageInjectionHost host = InjectedLanguageManager
                .getInstance(injectedFile.getProject())
                .getInjectionHost(injectedFile.getViewProvider());
        return host instanceof XmlAttributeValue value ? value : null;
    }

    public static @Nullable MyBatisOgnlSemanticModel semanticModel(
            @NotNull PsiFile injectedFile) {
        XmlAttributeValue host = host(injectedFile);
        return host == null ? null : MyBatisOgnlSemanticAnalyzer.analyze(host);
    }

    public static @NotNull PsiElement occurrenceHost(
            @NotNull PsiFile file,
            @NotNull MyBatisOgnlRange range) {
        ProgressManager.checkCanceled();
        int length = file.getTextLength();
        int offset = Math.max(0, Math.min(range.startOffset(), Math.max(0, length - 1)));
        PsiElement element = length == 0 ? file : file.findElementAt(offset);
        if (element == null) {
            return file;
        }
        TextRange occurrenceRange = new TextRange(range.startOffset(), range.endOffset());
        PsiElement current = element;
        while (current.getParent() != null
                && !current.getTextRange().contains(occurrenceRange)) {
            ProgressManager.checkCanceled();
            current = current.getParent();
        }
        while (!(current instanceof ContributedReferenceHost)
                && current.getParent() != null) {
            ProgressManager.checkCanceled();
            current = current.getParent();
        }
        return current;
    }

    public static @NotNull TextRange rangeInElement(
            @NotNull PsiElement element,
            @NotNull MyBatisOgnlRange range) {
        return new TextRange(range.startOffset(), range.endOffset())
                .shiftLeft(element.getTextRange().getStartOffset());
    }
}
