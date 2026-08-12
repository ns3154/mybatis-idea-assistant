package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyAccess;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 根据 ResultMap 嵌套结构推导当前可写 Java 类型并解析 property 路径。
 */
final class MyBatisResultPropertyPathResolver {
    private MyBatisResultPropertyPathResolver() {
    }

    static @NotNull MyBatisParameterPathResolution resolve(
            @NotNull PsiElement source,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        ProgressManager.checkCanceled();
        if (!source.isValid()) {
            return resolution(MyBatisParameterPathResolution.Status.SOURCE_INVALID);
        }
        Project project = source.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return resolution(MyBatisParameterPathResolution.Status.SOURCE_INVALID);
        }
        if (DumbService.isDumb(project)) {
            return resolution(MyBatisParameterPathResolution.Status.INDEX_NOT_READY);
        }
        XmlTag mapping = PsiTreeUtil.getParentOfType(source, XmlTag.class, false);
        XmlTag resultMap = resultMapRoot(mapping);
        if (mapping == null
                || resultMap == null
                || segmentIndex < 0
                || segmentIndex >= path.segments().size()) {
            return resolution(MyBatisParameterPathResolution.Status.UNKNOWN);
        }
        TypeState ownerTypes = ownerTypes(resultMap, mapping, new LinkedHashSet<>());
        if (ownerTypes.types().isEmpty()) {
            return resolution(MyBatisParameterPathResolution.Status.UNKNOWN);
        }
        return resolvePath(ownerTypes, path, segmentIndex);
    }

    static @NotNull MyBatisParameterPathResolution resolveConstructorArgument(
            @NotNull PsiElement source,
            @NotNull String argumentName) {
        ProgressManager.checkCanceled();
        if (!source.isValid()) {
            return resolution(MyBatisParameterPathResolution.Status.SOURCE_INVALID);
        }
        Project project = source.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return resolution(MyBatisParameterPathResolution.Status.SOURCE_INVALID);
        }
        if (DumbService.isDumb(project)) {
            return resolution(MyBatisParameterPathResolution.Status.INDEX_NOT_READY);
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(source, XmlTag.class, false);
        XmlTag resultMap = resultMapRoot(tag);
        if (tag == null || resultMap == null) {
            return resolution(MyBatisParameterPathResolution.Status.UNKNOWN);
        }
        TypeState root = resultMapTypes(resultMap, new LinkedHashSet<>());
        Set<PsiElement> targets = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        for (PsiType type : root.types()) {
            ProgressManager.checkCanceled();
            if (!(type instanceof PsiClassType classType)) {
                continue;
            }
            PsiClass psiClass = classType.resolve();
            if (psiClass == null) {
                continue;
            }
            for (PsiMethod constructor : psiClass.getConstructors()) {
                ProgressManager.checkCanceled();
                for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                    variants.add(parameter.getName());
                    if (argumentName.equals(parameter.getName())) {
                        targets.add(parameter);
                    }
                }
            }
        }
        return new MyBatisParameterPathResolution(
                targets.isEmpty()
                        ? MyBatisParameterPathResolution.Status.UNKNOWN
                        : MyBatisParameterPathResolution.Status.FOUND,
                List.copyOf(targets),
                variants.stream().sorted().toList());
    }

    static @NotNull Optional<List<String>> rootWritableProperties(
            @NotNull XmlTag resultMap) {
        ProgressManager.checkCanceled();
        if (!resultMap.isValid()
                || resultMap.getProject().isDisposed()
                || !resultMap.getProject().isOpen()
                || DumbService.isDumb(resultMap.getProject())
                || resultMapRoot(resultMap) != resultMap) {
            return Optional.empty();
        }
        TypeState root = resultMapTypes(resultMap, new LinkedHashSet<>());
        if (root.unknown() || root.types().size() != 1) {
            return Optional.empty();
        }
        PsiType type = root.types().getFirst();
        List<String> properties = MyBatisJavaPropertyResolver.variants(
                        type,
                        MyBatisJavaPropertyAccess.WRITE).stream()
                .filter(name -> {
                    ProgressManager.checkCanceled();
                    MyBatisJavaPropertyResolution resolution =
                            MyBatisJavaPropertyResolver.resolve(
                                    type,
                                    name,
                                    MyBatisJavaPropertyAccess.WRITE);
                    return !resolution.unknown() && resolution.targets().size() == 1;
                })
                .distinct()
                .sorted()
                .toList();
        return Optional.of(properties);
    }

    private static @NotNull MyBatisParameterPathResolution resolvePath(
            @NotNull TypeState ownerTypes,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        List<PsiType> types = ownerTypes.types();
        Set<PsiElement> targets = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        boolean unknown = ownerTypes.unknown();
        for (int index = 0; index <= segmentIndex; index++) {
            ProgressManager.checkCanceled();
            variants.clear();
            for (PsiType type : types) {
                variants.addAll(MyBatisJavaPropertyResolver.variants(
                        type,
                        MyBatisJavaPropertyAccess.WRITE));
            }
            targets.clear();
            List<PsiType> nextTypes = new ArrayList<>();
            String name = path.segments().get(index).name();
            for (PsiType type : types) {
                ProgressManager.checkCanceled();
                MyBatisJavaPropertyResolution property = MyBatisJavaPropertyResolver.resolve(
                        type,
                        name,
                        MyBatisJavaPropertyAccess.WRITE);
                targets.addAll(property.targets());
                nextTypes.addAll(property.types());
                unknown |= property.unknown();
            }
            types = applyIndexes(nextTypes, path.segments().get(index));
            if (targets.isEmpty() && types.isEmpty()) {
                if (index < segmentIndex) {
                    unknown = true;
                }
                break;
            }
        }
        if (!targets.isEmpty()) {
            List<PsiElement> sorted = new ArrayList<>(targets);
            sorted.sort(Comparator.comparing(MyBatisResultPropertyPathResolver::stableTargetKey));
            return new MyBatisParameterPathResolution(
                    MyBatisParameterPathResolution.Status.FOUND,
                    sorted,
                    variants.stream().sorted().toList());
        }
        return new MyBatisParameterPathResolution(
                unknown
                        ? MyBatisParameterPathResolution.Status.UNKNOWN
                        : MyBatisParameterPathResolution.Status.DEFINITE_MISSING,
                List.of(),
                variants.stream().sorted().toList());
    }

    private static @NotNull TypeState ownerTypes(
            @NotNull XmlTag resultMap,
            @NotNull XmlTag mapping,
            @NotNull Set<XmlTag> visited) {
        TypeState state = resultMapTypes(resultMap, visited);
        List<XmlTag> ancestors = new ArrayList<>();
        XmlTag current = mapping.getParentTag();
        while (current != null && current != resultMap) {
            ProgressManager.checkCanceled();
            ancestors.add(current);
            current = current.getParentTag();
        }
        java.util.Collections.reverse(ancestors);
        for (XmlTag ancestor : ancestors) {
            ProgressManager.checkCanceled();
            state = nestedTypes(state, ancestor, visited);
            if (state.types().isEmpty()) {
                return state;
            }
        }
        return state;
    }

    private static @NotNull TypeState resultMapTypes(
            @NotNull XmlTag resultMap,
            @NotNull Set<XmlTag> visited) {
        if (!visited.add(resultMap)) {
            return new TypeState(List.of(), true);
        }
        TypeState direct = typeAttribute(resultMap, "type");
        if (!direct.types().isEmpty()) {
            return direct;
        }
        XmlAttributeValue extendsValue = value(resultMap, "extends");
        if (extendsValue == null) {
            return direct;
        }
        for (com.intellij.psi.PsiReference reference : extendsValue.getReferences()) {
            ProgressManager.checkCanceled();
            if (reference instanceof MyBatisXmlSymbolReference symbolReference) {
                for (ResolveResult result : symbolReference.multiResolve(false)) {
                    if (result.getElement() instanceof XmlTag parentResultMap) {
                        TypeState inherited = resultMapTypes(parentResultMap, visited);
                        if (!inherited.types().isEmpty()) {
                            return inherited;
                        }
                    }
                }
            }
        }
        return new TypeState(List.of(), true);
    }

    private static @NotNull TypeState nestedTypes(
            @NotNull TypeState owner,
            @NotNull XmlTag container,
            @NotNull Set<XmlTag> visited) {
        String tagName = container.getName();
        if ("association".equals(tagName)) {
            TypeState explicit = typeAttribute(container, "javaType");
            return explicit.types().isEmpty()
                    ? propertyTypes(owner, container, false)
                    : explicit;
        }
        if ("collection".equals(tagName)) {
            TypeState explicit = typeAttribute(container, "ofType");
            return explicit.types().isEmpty()
                    ? propertyTypes(owner, container, true)
                    : explicit;
        }
        if ("case".equals(tagName)) {
            TypeState explicit = typeAttribute(container, "resultType");
            if (!explicit.types().isEmpty()) {
                return explicit;
            }
            XmlAttributeValue resultMapValue = value(container, "resultMap");
            if (resultMapValue != null) {
                for (com.intellij.psi.PsiReference reference : resultMapValue.getReferences()) {
                    ProgressManager.checkCanceled();
                    if (reference instanceof MyBatisXmlSymbolReference symbolReference) {
                        for (ResolveResult result : symbolReference.multiResolve(false)) {
                            if (result.getElement() instanceof XmlTag parentResultMap) {
                                return resultMapTypes(parentResultMap, visited);
                            }
                        }
                    }
                }
            }
        }
        return owner;
    }

    private static @NotNull TypeState propertyTypes(
            @NotNull TypeState owner,
            @NotNull XmlTag container,
            boolean collectionElement) {
        String propertyName = container.getAttributeValue("property");
        if (propertyName == null || propertyName.isBlank()) {
            return new TypeState(List.of(), true);
        }
        List<PsiType> types = owner.types();
        boolean unknown = owner.unknown();
        for (String segment : propertyName.trim().split("\\.")) {
            ProgressManager.checkCanceled();
            List<PsiType> next = new ArrayList<>();
            for (PsiType type : types) {
                MyBatisJavaPropertyResolution property = MyBatisJavaPropertyResolver.resolve(
                        type,
                        segment,
                        MyBatisJavaPropertyAccess.WRITE);
                next.addAll(property.types());
                unknown |= property.unknown();
            }
            types = next;
        }
        if (collectionElement) {
            List<PsiType> elements = new ArrayList<>();
            for (PsiType type : types) {
                PsiType element = MyBatisJavaPropertyResolver.indexedType(type);
                if (element == null) {
                    unknown = true;
                } else {
                    elements.add(element);
                }
            }
            types = elements;
        }
        return new TypeState(List.copyOf(types), unknown);
    }

    private static @NotNull TypeState typeAttribute(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        XmlAttributeValue value = value(tag, attributeName);
        if (value == null) {
            return new TypeState(List.of(), false);
        }
        Set<PsiType> types = new LinkedHashSet<>();
        for (com.intellij.psi.PsiReference reference : value.getReferences()) {
            ProgressManager.checkCanceled();
            if (reference instanceof MyBatisTypeReference typeReference) {
                for (ResolveResult result : typeReference.multiResolve(false)) {
                    if (result.getElement() instanceof PsiClass psiClass) {
                        types.add(JavaPsiFacade.getElementFactory(tag.getProject()).createType(psiClass));
                    } else if (result.getElement() instanceof PsiTypeElement typeElement) {
                        types.add(typeElement.getType());
                    }
                }
            }
        }
        return new TypeState(List.copyOf(types), types.isEmpty());
    }

    private static @NotNull List<PsiType> applyIndexes(
            @NotNull List<PsiType> sourceTypes,
            @NotNull MyBatisParameterExpressionParser.PathSegment segment) {
        List<PsiType> types = sourceTypes;
        for (int depth = 0; depth < segment.indexDepth(); depth++) {
            List<PsiType> next = new ArrayList<>();
            for (PsiType type : types) {
                ProgressManager.checkCanceled();
                PsiType indexed = MyBatisJavaPropertyResolver.indexedType(type);
                if (indexed != null) {
                    next.add(indexed);
                }
            }
            types = next;
        }
        return segment.dynamicIndex() ? List.of() : List.copyOf(types);
    }

    private static @Nullable XmlTag resultMapRoot(@Nullable XmlTag tag) {
        XmlTag current = tag;
        while (current != null) {
            ProgressManager.checkCanceled();
            if ("resultMap".equals(current.getName())) {
                XmlTag mapper = current.getParentTag();
                return mapper != null && MyBatisXmlModel.isMapperRoot(mapper) ? current : null;
            }
            current = current.getParentTag();
        }
        return null;
    }

    private static @Nullable XmlAttributeValue value(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        com.intellij.psi.xml.XmlAttribute attribute = tag.getAttribute(attributeName);
        return attribute != null && attributeName.equals(attribute.getName())
                ? attribute.getValueElement()
                : null;
    }

    private static @NotNull String stableTargetKey(@NotNull PsiElement target) {
        String file = target.getContainingFile() == null
                ? ""
                : target.getContainingFile().getName();
        return file + '#' + target.getTextOffset() + '#' + target;
    }

    private static @NotNull MyBatisParameterPathResolution resolution(
            @NotNull MyBatisParameterPathResolution.Status status) {
        return new MyBatisParameterPathResolution(status, List.of(), List.of());
    }

    private record TypeState(@NotNull List<PsiType> types, boolean unknown) {
        private TypeState {
            types = List.copyOf(types);
        }
    }
}
