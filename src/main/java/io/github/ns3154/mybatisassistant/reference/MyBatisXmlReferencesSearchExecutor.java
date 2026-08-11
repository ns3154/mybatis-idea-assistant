package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MyBatisXmlReferencesSearchExecutor
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {
    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters parameters,
            @NotNull Processor<? super PsiReference> consumer) {
        ProgressManager.checkCanceled();
        XmlTag declaration = declarationTag(parameters.getElementToSearch());
        SymbolDescriptor descriptor = descriptor(declaration);
        if (descriptor == null) {
            return true;
        }
        parameters.getOptimizer().searchWord(
                descriptor.id(),
                parameters.getEffectiveSearchScope(),
                UsageSearchContext.ANY,
                true,
                declaration);
        return true;
    }

    private static @Nullable XmlTag declarationTag(@NotNull PsiElement element) {
        if (element instanceof XmlTag tag) {
            return tag;
        }
        if (element instanceof XmlAttribute attribute) {
            return MyBatisReferenceSupport.containingTag(attribute);
        }
        if (element instanceof XmlAttributeValue value
                && value.getParent() instanceof XmlAttribute attribute) {
            return MyBatisReferenceSupport.containingTag(attribute);
        }
        return null;
    }

    private static @Nullable SymbolDescriptor descriptor(@Nullable XmlTag tag) {
        if (tag == null || tag.getParentTag() == null) {
            return null;
        }
        XmlTag mapper = MyBatisReferenceSupport.mapperRoot(tag);
        if (mapper == null || tag.getParentTag() != mapper) {
            return null;
        }
        MyBatisXmlSymbolKind kind = MyBatisXmlSymbolKind.fromMapperChildTag(tag.getName());
        if (kind != MyBatisXmlSymbolKind.RESULT_MAP
                && kind != MyBatisXmlSymbolKind.SQL_FRAGMENT) {
            return null;
        }
        String namespace = MyBatisXmlModel.namespace(mapper);
        String id = MyBatisXmlModel.symbolId(tag);
        return namespace == null || id == null
                ? null
                : new SymbolDescriptor(kind, namespace, id);
    }

    private record SymbolDescriptor(
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @NotNull String id) {
    }
}
