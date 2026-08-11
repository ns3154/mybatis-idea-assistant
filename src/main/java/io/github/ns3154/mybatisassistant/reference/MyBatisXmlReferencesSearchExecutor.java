package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
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
        if (descriptor != null) {
            schedule(parameters, descriptor.id(), declaration);
            return true;
        }
        String alternateName = alternateSearchName(parameters.getElementToSearch());
        if (alternateName != null) {
            schedule(parameters, alternateName, parameters.getElementToSearch());
        }
        return true;
    }

    private static void schedule(
            @NotNull ReferencesSearch.SearchParameters parameters,
            @NotNull String word,
            @NotNull PsiElement target) {
        parameters.getOptimizer().searchWord(
                word,
                parameters.getEffectiveSearchScope(),
                UsageSearchContext.ANY,
                true,
                target);
    }

    private static @Nullable String alternateSearchName(@NotNull PsiElement element) {
        if (element instanceof PsiClass psiClass) {
            return psiClass.getName();
        }
        if (element instanceof PsiLiteralExpression literal
                && literal.getValue() instanceof String name
                && io.github.ns3154.mybatisassistant.refactoring.MyBatisParamRenameProcessor
                .isParamLiteral(literal)) {
            return name;
        }
        if (element instanceof PsiField field) {
            return field.getName();
        }
        if (!(element instanceof PsiMethod method)) {
            return null;
        }
        String name = method.getName();
        if ((name.startsWith("get") || name.startsWith("set")) && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        return name.startsWith("is") && name.length() > 2
                ? decapitalize(name.substring(2))
                : null;
    }

    private static @NotNull String decapitalize(@NotNull String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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
