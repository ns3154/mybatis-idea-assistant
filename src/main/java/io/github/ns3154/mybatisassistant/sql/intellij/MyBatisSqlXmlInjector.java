package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapSegment;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 S7 虚拟 SQL 映射把同一 statement 的文本宿主接成一个 SQL PSI。
 */
public final class MyBatisSqlXmlInjector implements MultiHostInjector {
    @Override
    public void getLanguagesToInject(
            @NotNull MultiHostRegistrar registrar,
            @NotNull PsiElement context) {
        ProgressManager.checkCanceled();
        if (!(context instanceof XmlText xmlText)
                || !(xmlText instanceof PsiLanguageInjectionHost contextHost)
                || !contextHost.isValidHost()) {
            return;
        }
        XmlTag statement = statement(xmlText);
        if (statement == null) {
            return;
        }
        MyBatisSqlPsiResult result = MyBatisSqlPsiService
                .getInstance(statement.getProject())
                .parse(statement);
        if (!(result instanceof MyBatisSqlPsiResult.Ready ready)) {
            return;
        }
        List<InjectionPiece> pieces = pieces(
                statement.getContainingFile(),
                ready);
        if (pieces.isEmpty() || pieces.getFirst().host != contextHost) {
            return;
        }
        Language language = MyBatisSqlPsiService.language(ready.dialect());
        registrar.startInjecting(language);
        for (InjectionPiece piece : pieces) {
            ProgressManager.checkCanceled();
            registrar.addPlace(
                    emptyToNull(piece.prefix.toString()),
                    emptyToNull(piece.suffix.toString()),
                    piece.host,
                    piece.range);
        }
        registrar.doneInjecting();
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(XmlText.class);
    }

    private static @NotNull List<InjectionPiece> pieces(
            @NotNull PsiFile file,
            @NotNull MyBatisSqlPsiResult.Ready ready) {
        List<XmlText> textHosts = new ArrayList<>(PsiTreeUtil.findChildrenOfType(
                file,
                XmlText.class));
        List<InjectionPiece> result = new ArrayList<>();
        StringBuilder pendingSynthetic = new StringBuilder();
        String virtualText = ready.virtualSql().mappedText().text();
        String fileUrl = file.getVirtualFile() == null
                ? ""
                : file.getVirtualFile().getUrl();
        for (MyBatisSourceMapSegment segment
                : ready.virtualSql().mappedText().sourceMap().segments()) {
            ProgressManager.checkCanceled();
            String value = virtualText.substring(
                    segment.virtualRange().startOffset(),
                    segment.virtualRange().endOffset());
            if (!fileUrl.equals(segment.sourceRange().fileUrl())
                    || segment.kind() == MyBatisSourceMapKind.SYNTHETIC
                    || segment.kind() == MyBatisSourceMapKind.DECODED) {
                pendingSynthetic.append(value);
                continue;
            }
            PsiLanguageInjectionHost host = findHost(textHosts, segment);
            if (host == null) {
                pendingSynthetic.append(value);
                continue;
            }
            int hostStart = host.getTextRange().getStartOffset();
            InjectionPiece piece = new InjectionPiece(
                    host,
                    new TextRange(
                            segment.sourceRange().range().startOffset() - hostStart,
                            segment.sourceRange().range().endOffset() - hostStart));
            piece.prefix.append(pendingSynthetic);
            pendingSynthetic.setLength(0);
            result.add(piece);
        }
        if (!pendingSynthetic.isEmpty() && !result.isEmpty()) {
            result.getLast().suffix.append(pendingSynthetic);
        }
        return List.copyOf(result);
    }

    private static @Nullable PsiLanguageInjectionHost findHost(
            @NotNull List<XmlText> textHosts,
            @NotNull MyBatisSourceMapSegment segment) {
        int start = segment.sourceRange().range().startOffset();
        int end = segment.sourceRange().range().endOffset();
        for (XmlText xmlText : textHosts) {
            ProgressManager.checkCanceled();
            if (xmlText instanceof PsiLanguageInjectionHost host
                    && host.isValidHost()
                    && xmlText.getTextRange().containsRange(start, end)) {
                return host;
            }
        }
        return null;
    }

    private static @Nullable XmlTag statement(@NotNull XmlText xmlText) {
        XmlTag tag = PsiTreeUtil.getParentOfType(xmlText, XmlTag.class, false);
        while (tag != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(tag)) {
                return tag;
            }
            tag = tag.getParentTag();
        }
        return null;
    }

    private static @Nullable String emptyToNull(@NotNull String value) {
        return value.isEmpty() ? null : value;
    }

    private static final class InjectionPiece {
        private final PsiLanguageInjectionHost host;
        private final TextRange range;
        private final StringBuilder prefix = new StringBuilder();
        private final StringBuilder suffix = new StringBuilder();

        private InjectionPiece(
                @NotNull PsiLanguageInjectionHost host,
                @NotNull TextRange range) {
            this.host = host;
            this.range = range;
        }
    }
}
