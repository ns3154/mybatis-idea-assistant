package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MyBatisXmlReferenceContributor extends PsiReferenceContributor {
    private static final Set<String> RESULT_MAP_CONSUMERS = Set.of(
            "select",
            "association",
            "collection",
            "case");

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                new MapperAttributeReferenceProvider());
    }

    private static final class MapperAttributeReferenceProvider extends PsiReferenceProvider {
        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull com.intellij.psi.PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            if (!(element instanceof XmlAttributeValue value)) {
                return PsiReference.EMPTY_ARRAY;
            }
            return createReferences(value);
        }

        private static PsiReference @NotNull [] createReferences(
                @NotNull XmlAttributeValue value) {
            XmlAttribute namespaceAttribute = MyBatisReferenceSupport.exactAttribute(
                    value,
                    "namespace");
            if (namespaceAttribute != null) {
                XmlTag mapper = MyBatisReferenceSupport.containingTag(namespaceAttribute);
                return mapper != null && MyBatisXmlModel.isMapperRoot(mapper)
                        && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())
                        ? new PsiReference[]{new MyBatisNamespaceReference(value)}
                        : PsiReference.EMPTY_ARRAY;
            }

            XmlAttribute attribute = value.getParent() instanceof XmlAttribute parent
                    ? parent
                    : null;
            XmlTag tag = attribute == null ? null : MyBatisReferenceSupport.containingTag(attribute);
            XmlTag mapper = tag == null ? null : MyBatisReferenceSupport.mapperRoot(tag);
            String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
            if (attribute == null || tag == null || namespace == null) {
                return PsiReference.EMPTY_ARRAY;
            }

            if ("id".equals(attribute.getName())
                    && tag.getParentTag() == mapper
                    && MyBatisXmlModel.isStatement(tag)
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisStatementIdReference(value, namespace)};
            }
            if ("refid".equals(attribute.getName())
                    && "include".equals(tag.getName())
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisXmlSymbolReference(
                        value,
                        MyBatisXmlSymbolKind.SQL_FRAGMENT,
                        namespace)};
            }
            if ("resultMap".equals(attribute.getName())
                    && RESULT_MAP_CONSUMERS.contains(tag.getName())) {
                return resultMapReferences(value, namespace);
            }
            if ("extends".equals(attribute.getName())
                    && "resultMap".equals(tag.getName())
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisXmlSymbolReference(
                        value,
                        MyBatisXmlSymbolKind.RESULT_MAP,
                        namespace)};
            }
            return PsiReference.EMPTY_ARRAY;
        }

        private static PsiReference @NotNull [] resultMapReferences(
                @NotNull XmlAttributeValue value,
                @NotNull String namespace) {
            String rawValue = value.getValue();
            TextRange valueRange = MyBatisReferenceSupport.valueRange(value);
            List<PsiReference> references = new ArrayList<>();
            int segmentStart = 0;
            while (segmentStart <= rawValue.length()) {
                ProgressManager.checkCanceled();
                int comma = rawValue.indexOf(',', segmentStart);
                int segmentEnd = comma < 0 ? rawValue.length() : comma;
                int contentStart = segmentStart;
                int contentEnd = segmentEnd;
                while (contentStart < contentEnd
                        && Character.isWhitespace(rawValue.charAt(contentStart))) {
                    contentStart++;
                }
                while (contentEnd > contentStart
                        && Character.isWhitespace(rawValue.charAt(contentEnd - 1))) {
                    contentEnd--;
                }
                String symbol = rawValue.substring(contentStart, contentEnd);
                if (MyBatisReferenceSupport.isStaticReferenceValue(symbol)) {
                    references.add(new MyBatisXmlSymbolReference(
                            value,
                            MyBatisXmlSymbolKind.RESULT_MAP,
                            namespace,
                            TextRange.create(
                                    valueRange.getStartOffset() + contentStart,
                                    valueRange.getStartOffset() + contentEnd)));
                }
                if (comma < 0) {
                    break;
                }
                segmentStart = comma + 1;
            }
            return references.toArray(PsiReference.EMPTY_ARRAY);
        }
    }
}
