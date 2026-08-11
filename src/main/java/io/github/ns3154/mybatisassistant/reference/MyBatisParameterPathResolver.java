package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterBinding;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContext;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterRootMode;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将 Mapper XML 参数路径保守解析到 Java 参数、字段或 getter。
 */
final class MyBatisParameterPathResolver {
    private MyBatisParameterPathResolver() {
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
        StatementContext statement = statementContext(source);
        if (statement == null || segmentIndex < 0 || segmentIndex >= path.segments().size()) {
            return resolution(MyBatisParameterPathResolution.Status.UNKNOWN);
        }
        try {
            List<MyBatisParameterContext> contexts = parameterContexts(source, statement);
            if (contexts.isEmpty()) {
                return resolution(MyBatisParameterPathResolution.Status.UNKNOWN);
            }
            MyBatisParameterPathResolution resolution = resolveContexts(
                    contexts,
                    path,
                    segmentIndex);
            if (segmentIndex == 0
                    && resolution.status()
                    == MyBatisParameterPathResolution.Status.DEFINITE_MISSING
                    && hasUnmodeledDynamicRoot(
                    source,
                    statement.statement(),
                    path.segments().getFirst().name())) {
                return new MyBatisParameterPathResolution(
                        MyBatisParameterPathResolution.Status.UNKNOWN,
                        List.of(),
                        resolution.variants());
            }
            return resolution;
        } catch (IndexNotReadyException ignored) {
            return resolution(MyBatisParameterPathResolution.Status.INDEX_NOT_READY);
        }
    }

    private static @NotNull List<MyBatisParameterContext> parameterContexts(
            @NotNull PsiElement source,
            @NotNull StatementContext statement) {
        List<PsiClass> mapperClasses = MyBatisReferenceSupport.findMapperClasses(
                source,
                statement.namespace());
        List<PsiMethod> methods = MyBatisMapperMethodResolver.find(
                source,
                statement.namespace(),
                statement.statementId());
        List<MyBatisParameterContext> contexts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PsiClass mapper : mapperClasses) {
            ProgressManager.checkCanceled();
            for (PsiMethod method : methods) {
                ProgressManager.checkCanceled();
                if (mapper.findMethodBySignature(method, true) == null) {
                    continue;
                }
                MyBatisParameterContextResolution contextResolution =
                        MyBatisParameterContextResolver.resolve(mapper, method);
                if (contextResolution instanceof MyBatisParameterContextResolution.Found found
                        && seen.add(mapper.getQualifiedName() + "#" + method.getSignature(
                        com.intellij.psi.PsiSubstitutor.EMPTY))) {
                    contexts.add(found.context());
                }
            }
        }
        return List.copyOf(contexts);
    }

    private static @NotNull MyBatisParameterPathResolution resolveContexts(
            @NotNull List<MyBatisParameterContext> contexts,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        Set<PsiElement> targets = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        boolean unknown = false;
        for (MyBatisParameterContext context : contexts) {
            ProgressManager.checkCanceled();
            ContextResolution contextResolution = resolveContext(context, path, segmentIndex);
            targets.addAll(contextResolution.targets());
            variants.addAll(contextResolution.variants());
            unknown |= contextResolution.unknown();
        }
        if (!targets.isEmpty()) {
            List<PsiElement> sortedTargets = new ArrayList<>(targets);
            sortedTargets.sort(Comparator.comparing(MyBatisParameterPathResolver::stableTargetKey));
            return new MyBatisParameterPathResolution(
                    MyBatisParameterPathResolution.Status.FOUND,
                    sortedTargets,
                    variants.stream().sorted().toList());
        }
        return new MyBatisParameterPathResolution(
                unknown
                        ? MyBatisParameterPathResolution.Status.UNKNOWN
                        : MyBatisParameterPathResolution.Status.DEFINITE_MISSING,
                List.of(),
                variants.stream().sorted().toList());
    }

    private static @NotNull ContextResolution resolveContext(
            @NotNull MyBatisParameterContext context,
            @NotNull MyBatisParameterExpressionParser.ParameterPath path,
            int segmentIndex) {
        List<TypeBranch> branches = new ArrayList<>();
        Set<PsiElement> segmentTargets = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        boolean unknown = false;
        for (int index = 0; index <= segmentIndex; index++) {
            ProgressManager.checkCanceled();
            MyBatisParameterExpressionParser.PathSegment segment = path.segments().get(index);
            segmentTargets.clear();
            if (index == 0) {
                variants.addAll(rootVariants(context));
                RootResolution root = resolveRoot(context, segment.name());
                segmentTargets.addAll(root.targets());
                branches = applyIndexes(root.branches(), segment);
                unknown |= root.unknown() || branches.stream().anyMatch(TypeBranch::unknown);
            } else {
                variants.clear();
                variants.addAll(propertyVariants(branches));
                PropertyResolution property = resolveProperty(branches, segment.name());
                segmentTargets.addAll(property.targets());
                branches = applyIndexes(property.branches(), segment);
                unknown |= property.unknown() || branches.stream().anyMatch(TypeBranch::unknown);
            }
            if (segmentTargets.isEmpty() && branches.isEmpty()) {
                if (index < segmentIndex) {
                    unknown = true;
                }
                break;
            }
        }
        return new ContextResolution(List.copyOf(segmentTargets), List.copyOf(variants), unknown);
    }

    private static @NotNull RootResolution resolveRoot(
            @NotNull MyBatisParameterContext context,
            @NotNull String name) {
        List<MyBatisParameterBinding> bindings = context.findBindings(name);
        if (!bindings.isEmpty()) {
            List<PsiElement> targets = bindings.stream()
                    .map(MyBatisParameterBinding::parameter)
                    .map(PsiElement.class::cast)
                    .toList();
            List<TypeBranch> branches = bindings.stream()
                    .map(binding -> new TypeBranch(binding.type(), false))
                    .toList();
            return new RootResolution(targets, branches, false);
        }
        if (context.rootMode() == MyBatisParameterRootMode.NAMED) {
            return new RootResolution(List.of(), List.of(), false);
        }
        if (context.dynamicMapRoot()) {
            return new RootResolution(List.of(), List.of(), true);
        }
        return propertyResolution(context.directType(), name).asRoot();
    }

    private static @NotNull PropertyResolution resolveProperty(
            @NotNull List<TypeBranch> sourceBranches,
            @NotNull String name) {
        Set<PsiElement> targets = new LinkedHashSet<>();
        List<TypeBranch> branches = new ArrayList<>();
        boolean unknown = false;
        for (TypeBranch source : sourceBranches) {
            ProgressManager.checkCanceled();
            if (source.unknown()) {
                unknown = true;
                continue;
            }
            PropertyResolution resolved = propertyResolution(source.type(), name);
            targets.addAll(resolved.targets());
            branches.addAll(resolved.branches());
            unknown |= resolved.unknown();
        }
        return new PropertyResolution(List.copyOf(targets), List.copyOf(branches), unknown);
    }

    private static @NotNull PropertyResolution propertyResolution(
            @Nullable PsiType sourceType,
            @NotNull String name) {
        if (sourceType == null || isDynamicContainer(sourceType)) {
            return new PropertyResolution(List.of(), List.of(), true);
        }
        if (!(sourceType instanceof PsiClassType classType)) {
            return new PropertyResolution(List.of(), List.of(), false);
        }
        PsiClassType.ClassResolveResult classResult = classType.resolveGenerics();
        PsiClass psiClass = classResult.getElement();
        if (psiClass == null || psiClass.getQualifiedName() == null) {
            return new PropertyResolution(List.of(), List.of(), true);
        }
        Set<PsiElement> targets = new LinkedHashSet<>();
        List<TypeBranch> branches = new ArrayList<>();
        for (PsiMethod method : psiClass.getAllMethods()) {
            ProgressManager.checkCanceled();
            if (!isGetter(method, name)) {
                continue;
            }
            PsiType returnType = method.getReturnType();
            if (returnType != null) {
                targets.add(method);
                branches.add(new TypeBranch(substituteMemberType(
                        psiClass,
                        classResult.getSubstitutor(),
                        method.getContainingClass(),
                        returnType), false));
            }
        }
        if (targets.isEmpty()) {
            PsiField field = psiClass.findFieldByName(name, true);
            if (field != null) {
                targets.add(field);
                branches.add(new TypeBranch(substituteMemberType(
                        psiClass,
                        classResult.getSubstitutor(),
                        field.getContainingClass(),
                        field.getType()), false));
            }
        }
        return new PropertyResolution(List.copyOf(targets), List.copyOf(branches), false);
    }

    private static @NotNull PsiType substituteMemberType(
            @NotNull PsiClass sourceClass,
            @NotNull PsiSubstitutor sourceSubstitutor,
            @Nullable PsiClass declaringClass,
            @NotNull PsiType memberType) {
        PsiSubstitutor substitutor = declaringClass == null || declaringClass.equals(sourceClass)
                ? sourceSubstitutor
                : TypeConversionUtil.getSuperClassSubstitutor(
                        declaringClass,
                        sourceClass,
                        sourceSubstitutor);
        PsiType substituted = substitutor.substitute(memberType);
        return substituted == null ? memberType : substituted;
    }

    private static boolean isGetter(@NotNull PsiMethod method, @NotNull String propertyName) {
        if (method.hasModifierProperty(PsiModifier.STATIC)
                || method.getParameterList().getParametersCount() != 0
                || PsiTypes.voidType().equals(method.getReturnType())
                || "getClass".equals(method.getName())) {
            return false;
        }
        String capitalized = Character.toUpperCase(propertyName.charAt(0))
                + propertyName.substring(1);
        return ("get" + capitalized).equals(method.getName())
                || ("is" + capitalized).equals(method.getName());
    }

    private static @NotNull List<TypeBranch> applyIndexes(
            @NotNull List<TypeBranch> sourceBranches,
            @NotNull MyBatisParameterExpressionParser.PathSegment segment) {
        List<TypeBranch> branches = sourceBranches;
        for (int depth = 0; depth < segment.indexDepth(); depth++) {
            ProgressManager.checkCanceled();
            List<TypeBranch> next = new ArrayList<>();
            for (TypeBranch branch : branches) {
                PsiType elementType = indexedType(branch.type());
                next.add(elementType == null
                        ? new TypeBranch(branch.type(), true)
                        : new TypeBranch(elementType, branch.unknown()));
            }
            branches = next;
        }
        if (segment.dynamicIndex()) {
            return branches.stream()
                    .map(branch -> new TypeBranch(branch.type(), true))
                    .toList();
        }
        return List.copyOf(branches);
    }

    private static @Nullable PsiType indexedType(@NotNull PsiType sourceType) {
        if (sourceType instanceof PsiArrayType arrayType) {
            return arrayType.getComponentType();
        }
        if (!(sourceType instanceof PsiClassType classType)) {
            return null;
        }
        String rawType = classType.rawType().getCanonicalText();
        PsiClass resolved = classType.resolve();
        if (!("java.util.List".equals(rawType)
                || "java.util.Collection".equals(rawType)
                || "java.lang.Iterable".equals(rawType)
                || resolved != null && (InheritanceUtil.isInheritor(resolved, "java.util.Collection")
                || InheritanceUtil.isInheritor(resolved, "java.lang.Iterable")
                || "java.util.List".equals(resolved.getQualifiedName())))) {
            return null;
        }
        PsiType[] parameters = classType.getParameters();
        return parameters.length == 1 ? parameters[0] : null;
    }

    private static boolean isDynamicContainer(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType classType)) {
            return false;
        }
        if ("java.util.Map".equals(classType.rawType().getCanonicalText())) {
            return true;
        }
        PsiClass resolved = classType.resolve();
        return resolved != null
                && ("java.util.Map".equals(resolved.getQualifiedName())
                || InheritanceUtil.isInheritor(resolved, "java.util.Map"));
    }

    private static @NotNull List<String> rootVariants(@NotNull MyBatisParameterContext context) {
        Set<String> names = new LinkedHashSet<>();
        context.bindings().forEach(binding -> names.add(binding.name()));
        if (context.rootMode() == MyBatisParameterRootMode.DIRECT
                && !context.dynamicMapRoot()) {
            names.addAll(propertyNames(context.directType()));
        }
        return List.copyOf(names);
    }

    private static @NotNull List<String> propertyVariants(@NotNull List<TypeBranch> branches) {
        Set<String> names = new LinkedHashSet<>();
        for (TypeBranch branch : branches) {
            ProgressManager.checkCanceled();
            if (!branch.unknown()) {
                names.addAll(propertyNames(branch.type()));
            }
        }
        return List.copyOf(names);
    }

    private static @NotNull List<String> propertyNames(@Nullable PsiType type) {
        if (!(type instanceof PsiClassType classType) || isDynamicContainer(type)) {
            return List.of();
        }
        PsiClass psiClass = classType.resolve();
        if (psiClass == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (PsiField field : psiClass.getAllFields()) {
            ProgressManager.checkCanceled();
            names.add(field.getName());
        }
        for (PsiMethod method : psiClass.getAllMethods()) {
            ProgressManager.checkCanceled();
            String property = propertyName(method);
            if (property != null) {
                names.add(property);
            }
        }
        return List.copyOf(names);
    }

    private static @Nullable String propertyName(@NotNull PsiMethod method) {
        if (method.hasModifierProperty(PsiModifier.STATIC)
                || method.getParameterList().getParametersCount() != 0
                || PsiTypes.voidType().equals(method.getReturnType())
                || "getClass".equals(method.getName())) {
            return null;
        }
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static @NotNull String decapitalize(@NotNull String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static @Nullable StatementContext statementContext(@NotNull PsiElement source) {
        XmlTag tag = PsiTreeUtil.getParentOfType(source, XmlTag.class, false);
        if (tag == null) {
            return null;
        }
        XmlTag mapper = MyBatisReferenceSupport.mapperRoot(tag);
        if (mapper == null) {
            return null;
        }
        XmlTag current = tag;
        while (current.getParentTag() != null && current.getParentTag() != mapper) {
            ProgressManager.checkCanceled();
            current = current.getParentTag();
        }
        if (current.getParentTag() != mapper || !MyBatisXmlModel.isStatement(current)) {
            return null;
        }
        String namespace = MyBatisXmlModel.namespace(mapper);
        String statementId = MyBatisXmlModel.statementId(current);
        return namespace == null || statementId == null
                ? null
                : new StatementContext(namespace, statementId, current);
    }

    private static boolean hasUnmodeledDynamicRoot(
            @NotNull PsiElement source,
            @NotNull XmlTag statement,
            @NotNull String rootName) {
        XmlTag sourceTag = PsiTreeUtil.getParentOfType(source, XmlTag.class, false);
        XmlTag current = sourceTag;
        while (current != null && current != statement) {
            ProgressManager.checkCanceled();
            if ("foreach".equals(current.getName())) {
                String item = current.getAttributeValue("item");
                String index = current.getAttributeValue("index");
                if (rootName.equals(item) || rootName.equals(index)
                        || current != sourceTag
                        || !isCollectionAttribute(source, current)) {
                    return true;
                }
            }
            current = current.getParentTag();
        }
        for (XmlTag descendant : PsiTreeUtil.findChildrenOfType(statement, XmlTag.class)) {
            ProgressManager.checkCanceled();
            if ("bind".equals(descendant.getName())
                    && rootName.equals(descendant.getAttributeValue("name"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCollectionAttribute(
            @NotNull PsiElement source,
            @NotNull XmlTag foreach) {
        return source.getParent() instanceof com.intellij.psi.xml.XmlAttribute attribute
                && "collection".equals(attribute.getName())
                && attribute.getParent() == foreach;
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

    private record StatementContext(
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull XmlTag statement) {
    }

    private record TypeBranch(@NotNull PsiType type, boolean unknown) {
    }

    private record RootResolution(
            @NotNull List<PsiElement> targets,
            @NotNull List<TypeBranch> branches,
            boolean unknown) {
    }

    private record PropertyResolution(
            @NotNull List<PsiElement> targets,
            @NotNull List<TypeBranch> branches,
            boolean unknown) {
        private @NotNull RootResolution asRoot() {
            return new RootResolution(targets, branches, unknown);
        }
    }

    private record ContextResolution(
            @NotNull List<PsiElement> targets,
            @NotNull List<String> variants,
            boolean unknown) {
    }
}
