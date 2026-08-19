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
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
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
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlToken.class)
                        .withElementType(XmlTokenType.XML_DATA_CHARACTERS),
                new MapperTextReferenceProvider());
    }

    private static final class MapperAttributeReferenceProvider extends PsiReferenceProvider {
        @Override
        public @NotNull PsiReference[] getReferencesByElement(
                @NotNull com.intellij.psi.PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            if (!(element instanceof XmlAttributeValue value)) {
                return PsiReference.EMPTY_ARRAY;
            }
            return createReferences(value);
        }

        private static @NotNull PsiReference[] createReferences(
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
            if (attribute != null
                    && tag != null
                    && isConfiguredTypeAliasTarget(attribute, tag)
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisTypeReference(value)};
            }
            XmlTag mapper = tag == null ? null : MyBatisReferenceSupport.mapperRoot(tag);
            String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
            if (attribute == null || tag == null || namespace == null) {
                return PsiReference.EMPTY_ARRAY;
            }

            if (isParameterPathAttribute(attribute, tag)) {
                return directParameterReferences(value).toArray(PsiReference.EMPTY_ARRAY);
            }
            if (isTypeAttribute(attribute, tag)
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisTypeReference(value)};
            }
            if (isResultPropertyAttribute(attribute, tag)
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return resultPropertyReferences(value).toArray(PsiReference.EMPTY_ARRAY);
            }
            if (isConstructorArgumentAttribute(attribute, tag)
                    && MyBatisReferenceSupport.isStaticReferenceValue(value.getValue())) {
                return new PsiReference[]{new MyBatisConstructorArgumentReference(value)};
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

        private static boolean isParameterPathAttribute(
                @NotNull XmlAttribute attribute,
                @NotNull XmlTag tag) {
            return "collection".equals(attribute.getName()) && "foreach".equals(tag.getName())
                    || "keyProperty".equals(attribute.getName())
                    && ("insert".equals(tag.getName())
                    || "update".equals(tag.getName())
                    || "selectKey".equals(tag.getName()));
        }

        private static boolean isTypeAttribute(
                @NotNull XmlAttribute attribute,
                @NotNull XmlTag tag) {
            String name = attribute.getName();
            return "parameterType".equals(name) && MyBatisXmlModel.isStatement(tag)
                    || "resultType".equals(name)
                    && (MyBatisXmlModel.isStatement(tag) || "case".equals(tag.getName()))
                    || "type".equals(name) && "resultMap".equals(tag.getName())
                    || "javaType".equals(name)
                    && Set.of(
                    "id",
                    "result",
                    "association",
                    "collection",
                    "arg",
                    "idArg",
                    "discriminator").contains(tag.getName())
                    || "ofType".equals(name) && "collection".equals(tag.getName());
        }

        private static boolean isConfiguredTypeAliasTarget(
                @NotNull XmlAttribute attribute,
                @NotNull XmlTag tag) {
            XmlTag typeAliases = tag.getParentTag();
            XmlTag configuration = typeAliases == null ? null : typeAliases.getParentTag();
            return "type".equals(attribute.getName())
                    && "typeAlias".equals(tag.getName())
                    && typeAliases != null
                    && "typeAliases".equals(typeAliases.getName())
                    && configuration != null
                    && "configuration".equals(configuration.getName());
        }

        private static boolean isResultPropertyAttribute(
                @NotNull XmlAttribute attribute,
                @NotNull XmlTag tag) {
            return "property".equals(attribute.getName())
                    && Set.of("id", "result", "association", "collection")
                    .contains(tag.getName())
                    && MapperAttributeReferenceProvider.resultMapRoot(tag) != null;
        }

        private static boolean isConstructorArgumentAttribute(
                @NotNull XmlAttribute attribute,
                @NotNull XmlTag tag) {
            XmlTag parent = tag.getParentTag();
            return "name".equals(attribute.getName())
                    && ("arg".equals(tag.getName()) || "idArg".equals(tag.getName()))
                    && parent != null
                    && "constructor".equals(parent.getName())
                    && MapperAttributeReferenceProvider.resultMapRoot(tag) != null;
        }

        private static @NotNull List<PsiReference> resultPropertyReferences(
                @NotNull XmlAttributeValue value) {
            List<MyBatisParameterExpressionParser.ParameterPath> paths =
                    MyBatisParameterExpressionParser.parseCommaSeparatedPaths(value.getValue());
            if (paths.size() != 1) {
                return List.of();
            }
            List<PsiReference> references = new ArrayList<>();
            MyBatisParameterExpressionParser.ParameterPath path = paths.getFirst();
            TextRange valueRange = MyBatisReferenceSupport.valueRange(value);
            for (int index = 0; index < path.segments().size(); index++) {
                TextRange range = path.segments().get(index).range()
                        .shiftRight(valueRange.getStartOffset());
                references.add(new MyBatisResultPropertyReference(value, range, path, index));
            }
            return List.copyOf(references);
        }

        private static XmlTag resultMapRoot(@NotNull XmlTag tag) {
            XmlTag current = tag;
            while (current != null) {
                ProgressManager.checkCanceled();
                if ("resultMap".equals(current.getName())) {
                    XmlTag parent = current.getParentTag();
                    return parent != null && MyBatisXmlModel.isMapperRoot(parent) ? current : null;
                }
                current = current.getParentTag();
            }
            return null;
        }

        private static @NotNull List<PsiReference> directParameterReferences(
                @NotNull XmlAttributeValue value) {
            List<PsiReference> references = new ArrayList<>();
            TextRange valueRange = MyBatisReferenceSupport.valueRange(value);
            for (MyBatisParameterExpressionParser.ParameterPath path
                    : MyBatisParameterExpressionParser.parseCommaSeparatedPaths(value.getValue())) {
                addPathReferences(value, valueRange, path, references);
            }
            return List.copyOf(references);
        }

        private static @NotNull PsiReference[] resultMapReferences(
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

    private static final class MapperTextReferenceProvider extends PsiReferenceProvider {
        @Override
        public @NotNull PsiReference[] getReferencesByElement(
                @NotNull com.intellij.psi.PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            if (!(element instanceof XmlToken token)) {
                return PsiReference.EMPTY_ARRAY;
            }
            return parameterReferences(token, token.getText(), TextRange.EMPTY_RANGE)
                    .toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    private static @NotNull List<PsiReference> parameterReferences(
            @NotNull com.intellij.psi.PsiElement element,
            @NotNull String text,
            @NotNull TextRange baseRange) {
        List<PsiReference> references = new ArrayList<>();
        for (MyBatisParameterExpressionParser.ParameterPath path
                : MyBatisParameterExpressionParser.parsePlaceholders(text)) {
            addPathReferences(element, baseRange, path, references);
        }
        return List.copyOf(references);
    }

    private static void addPathReferences(
            @NotNull com.intellij.psi.PsiElement element,
            @NotNull TextRange baseRange,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            @NotNull List<PsiReference> references) {
        for (int index = 0; index < path.segments().size(); index++) {
            ProgressManager.checkCanceled();
            TextRange range = path.segments().get(index).range().shiftRight(baseRange.getStartOffset());
            references.add(new MyBatisParameterReference(element, range, path, index));
        }
    }
}
