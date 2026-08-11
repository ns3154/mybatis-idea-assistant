package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class MyBatisXmlSymbolReference extends PsiPolyVariantReferenceBase<XmlAttributeValue> {
    private final MyBatisXmlSymbolKind kind;
    private final String localNamespace;

    MyBatisXmlSymbolReference(
            @NotNull XmlAttributeValue element,
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String localNamespace) {
        this(
                element,
                kind,
                localNamespace,
                MyBatisReferenceSupport.valueRange(element));
    }

    MyBatisXmlSymbolReference(
            @NotNull XmlAttributeValue element,
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String localNamespace,
            @NotNull TextRange range) {
        super(element, range, false);
        this.kind = kind;
        this.localNamespace = localNamespace;
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        ProgressManager.checkCanceled();
        if (!getElement().isValid()) {
            return ResolveResult.EMPTY_ARRAY;
        }
        Project project = getElement().getProject();
        String value = getRangeInElement().substring(getElement().getText()).trim();
        if (project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)
                || !MyBatisReferenceSupport.isStaticReferenceValue(value)) {
            return ResolveResult.EMPTY_ARRAY;
        }
        QualifiedSymbol symbol = QualifiedSymbol.parse(value, localNamespace);
        try {
            List<ResolveResult> results = new ArrayList<>();
            for (XmlTag target : MyBatisXmlSymbolLocator.find(
                    project,
                    kind,
                    symbol.namespace(),
                    symbol.id(),
                    MyBatisReferenceSupport.resolveScope(getElement()))) {
                ProgressManager.checkCanceled();
                results.add(new PsiElementResolveResult(target));
            }
            return results.toArray(ResolveResult.EMPTY_ARRAY);
        } catch (IndexNotReadyException ignored) {
            return ResolveResult.EMPTY_ARRAY;
        }
    }

    private record QualifiedSymbol(@NotNull String namespace, @NotNull String id) {
        private static @NotNull QualifiedSymbol parse(
                @NotNull String value,
                @NotNull String localNamespace) {
            int separator = value.lastIndexOf('.');
            return separator <= 0 || separator == value.length() - 1
                    ? new QualifiedSymbol(localNamespace, value)
                    : new QualifiedSymbol(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
