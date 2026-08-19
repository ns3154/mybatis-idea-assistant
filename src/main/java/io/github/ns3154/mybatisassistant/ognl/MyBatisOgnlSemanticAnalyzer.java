package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyAccess;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisJavaPropertyResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterBinding;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterBindingCertainty;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterBindingKind;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContext;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisParameterContextResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在不执行用户表达式的前提下，为 XML OGNL 属性建立词法作用域和保守 Java 类型。
 */
public final class MyBatisOgnlSemanticAnalyzer {
    private static final Key<CachedValue<MyBatisOgnlSemanticModel>> SEMANTIC_CACHE_KEY =
            Key.create("mybatis.idea.assistant.ognl.semantic");
    private static final String JAVA_LANG_STRING = "java.lang.String";
    private static final String JAVA_UTIL_MAP = "java.util.Map";
    private static final Set<MyBatisOgnlOperator> BOOLEAN_OPERATORS = Set.of(
            MyBatisOgnlOperator.LESS,
            MyBatisOgnlOperator.LESS_OR_EQUAL,
            MyBatisOgnlOperator.GREATER,
            MyBatisOgnlOperator.GREATER_OR_EQUAL,
            MyBatisOgnlOperator.IN,
            MyBatisOgnlOperator.NOT_IN,
            MyBatisOgnlOperator.INSTANCE_OF,
            MyBatisOgnlOperator.EQUAL,
            MyBatisOgnlOperator.NOT_EQUAL,
            MyBatisOgnlOperator.AND,
            MyBatisOgnlOperator.OR);

    private MyBatisOgnlSemanticAnalyzer() {
    }

    public static @NotNull MyBatisOgnlSemanticModel analyze(
            @NotNull XmlAttributeValue source) {
        return analyzeInternal(source, true);
    }

    static @NotNull MyBatisOgnlSemanticModel analyzeUncached(
            @NotNull XmlAttributeValue source) {
        return analyzeInternal(source, false);
    }

    private static @NotNull MyBatisOgnlSemanticModel analyzeInternal(
            @NotNull XmlAttributeValue source,
            boolean useCache) {
        ProgressManager.checkCanceled();
        Project project = source.getProject();
        if (!source.isValid() || project.isDisposed() || !project.isOpen()) {
            return lifecycle(
                    MyBatisOgnlParser.parse(ognlText(source)),
                    MyBatisOgnlSemanticStatus.SOURCE_INVALID);
        }
        if (DumbService.isDumb(project)) {
            return lifecycle(
                    MyBatisOgnlParser.parse(ognlText(source)),
                    MyBatisOgnlSemanticStatus.INDEX_NOT_READY);
        }
        try {
            MyBatisOgnlSemanticModel model = useCache
                    ? analyzeCached(source, project)
                    : compute(source);
            if (!source.isValid() || project.isDisposed() || !project.isOpen()) {
                return lifecycle(
                        model.parseResult(),
                        MyBatisOgnlSemanticStatus.SOURCE_INVALID);
            }
            if (DumbService.isDumb(project)) {
                return lifecycle(
                        model.parseResult(),
                        MyBatisOgnlSemanticStatus.INDEX_NOT_READY);
            }
            return model;
        } catch (IndexNotReadyException ignored) {
            return lifecycle(
                    MyBatisOgnlParser.parse(ognlText(source)),
                    MyBatisOgnlSemanticStatus.INDEX_NOT_READY);
        } catch (NonCacheableSemanticException exception) {
            return exception.model();
        }
    }

    private static @NotNull MyBatisOgnlSemanticModel analyzeCached(
            @NotNull XmlAttributeValue source,
            @NotNull Project project) {
        return CachedValuesManager.getCachedValue(source, SEMANTIC_CACHE_KEY, () -> {
            MyBatisOgnlSemanticModel model = compute(source);
            MyBatisOgnlSemanticStatus status = model.rootResult().status();
            if (status == MyBatisOgnlSemanticStatus.SOURCE_INVALID
                    || status == MyBatisOgnlSemanticStatus.INDEX_NOT_READY) {
                throw new NonCacheableSemanticException(model);
            }
            List<Object> dependencies = new ArrayList<>();
            if (source.getContainingFile() != null) {
                dependencies.add((ModificationTracker) () -> source.getContainingFile().isValid()
                        ? source.getContainingFile().getModificationStamp()
                        : Long.MAX_VALUE);
            }
            dependencies.add(PsiModificationTracker.getInstance(project)
                    .forLanguage(JavaLanguage.INSTANCE));
            dependencies.add(DumbService.getInstance(project).getModificationTracker());
            dependencies.add(ProjectRootModificationTracker.getInstance(project));
            return CachedValueProvider.Result.create(model, dependencies);
        });
    }

    private static @NotNull MyBatisOgnlSemanticModel compute(
            @NotNull XmlAttributeValue source) {
        DecodedOgnl decoded = decodeOgnl(source);
        MyBatisOgnlParseResult parsed = MyBatisOgnlParser.parse(decoded.text());
        List<Branch> branches = branches(source);
        if (branches.isEmpty()) {
            return lifecycle(parsed, MyBatisOgnlSemanticStatus.UNSUPPORTED_SOURCE);
        }
        List<BranchAnalysis> analyses = new ArrayList<>();
        for (Branch branch : branches) {
            ProgressManager.checkCanceled();
            addLexicalBindings(source, branch);
            analyses.add(new Inferencer(source, branch).analyze(parsed.root()));
        }
        return remapOccurrences(merge(parsed, analyses), decoded);
    }

    private static @NotNull List<Branch> branches(@NotNull XmlAttributeValue source) {
        XmlTag hostTag = PsiTreeUtil.getParentOfType(source, XmlTag.class, false);
        XmlTag statement = nearestStatement(hostTag);
        XmlTag mapper = mapperRoot(statement);
        String namespace = mapper == null ? null : MyBatisXmlModel.namespace(mapper);
        String statementId = statement == null ? null : MyBatisXmlModel.statementId(statement);
        if (hostTag == null
                || statement == null
                || mapper == null
                || namespace == null
                || statementId == null) {
            return List.of();
        }
        List<Branch> branches = new ArrayList<>();
        for (PsiMethod method : MyBatisMapperMethodResolver.find(
                source,
                namespace,
                statementId)) {
            ProgressManager.checkCanceled();
            PsiClass mapperClass = method.getContainingClass();
            if (mapperClass == null) {
                continue;
            }
            MyBatisParameterContextResolution resolved =
                    MyBatisParameterContextResolver.resolve(mapperClass, method);
            if (resolved instanceof MyBatisParameterContextResolution.Found found) {
                branches.add(baseBranch(source, found.context(), statement, hostTag));
            }
        }
        return List.copyOf(branches);
    }

    private static @NotNull Branch baseBranch(
            @NotNull XmlAttributeValue source,
            @NotNull MyBatisParameterContext context,
            @NotNull XmlTag statement,
            @NotNull XmlTag hostTag) {
        Branch branch = new Branch(context.directType(), context.dynamicMapRoot(), statement, hostTag);
        for (MyBatisParameterBinding binding : context.bindings()) {
            ProgressManager.checkCanceled();
            PsiElement target = binding.kind() == MyBatisParameterBindingKind.EXPLICIT
                    ? Objects.requireNonNullElse(
                    MyBatisAnnotationModel.explicitParameterNameElement(binding.parameter()),
                    binding.parameter())
                    : binding.parameter();
            branch.add(new Binding(
                    binding.name(),
                    binding.type(),
                    target,
                    binding.certainty() == MyBatisParameterBindingCertainty.DEFINITE));
        }
        if (branch.find("_parameter").isEmpty()) {
            branch.add(new Binding(
                    "_parameter",
                    type(source, JAVA_UTIL_MAP),
                    context.method(),
                    true));
        }
        branch.add(new Binding(
                "_databaseId",
                type(source, JAVA_LANG_STRING),
                null,
                true));
        return branch;
    }

    private static void addLexicalBindings(
            @NotNull XmlAttributeValue source,
            @NotNull Branch branch) {
        List<XmlTag> path = path(branch.statement(), branch.hostTag());
        XmlTag parent = branch.statement();
        for (XmlTag child : path) {
            ProgressManager.checkCanceled();
            addPrecedingBinds(source, parent, child, branch);
            if ("foreach".equals(child.getName()) && child != branch.hostTag()) {
                addForeachBindings(source, child, branch);
            }
            parent = child;
        }
    }

    private static void addPrecedingBinds(
            @NotNull XmlAttributeValue source,
            @NotNull XmlTag parent,
            @NotNull XmlTag child,
            @NotNull Branch branch) {
        for (XmlTag sibling : parent.getSubTags()) {
            ProgressManager.checkCanceled();
            if (sibling == child) {
                return;
            }
            if (!"bind".equals(sibling.getName())) {
                continue;
            }
            String name = normalizedAttribute(sibling, "name");
            XmlAttributeValue value = attributeValue(sibling, "value");
            if (name == null || value == null) {
                continue;
            }
            State state = new Inferencer(value, branch).inferText(ognlText(value));
            branch.shadow(new Binding(
                    name,
                    state.singleType(),
                    attributeValue(sibling, "name"),
                    state.status() == MyBatisOgnlSemanticStatus.FOUND));
        }
    }

    private static void addForeachBindings(
            @NotNull XmlAttributeValue source,
            @NotNull XmlTag foreach,
            @NotNull Branch branch) {
        XmlAttributeValue collection = attributeValue(foreach, "collection");
        State collectionState = collection == null
                ? State.unknown()
                : new Inferencer(collection, branch).inferText(ognlText(collection));
        PsiType itemType = indexedType(collectionState);
        String item = Objects.requireNonNullElse(normalizedAttribute(foreach, "item"), "item");
        String index = Objects.requireNonNullElse(normalizedAttribute(foreach, "index"), "index");
        branch.shadow(new Binding(
                item,
                itemType,
                Objects.requireNonNullElse(attributeValue(foreach, "item"), foreach),
                itemType != null));
        PsiType indexType = collectionIndexType(source, collectionState);
        branch.shadow(new Binding(
                index,
                indexType,
                Objects.requireNonNullElse(attributeValue(foreach, "index"), foreach),
                indexType != null));
    }

    private static @Nullable PsiType indexedType(@NotNull State collection) {
        if (collection.status() != MyBatisOgnlSemanticStatus.FOUND) {
            return null;
        }
        Set<String> canonicalTypes = new LinkedHashSet<>();
        PsiType candidate = null;
        for (PsiType type : collection.types()) {
            ProgressManager.checkCanceled();
            if (MyBatisJavaPropertyResolver.isDynamicMap(type)) {
                return null;
            }
            PsiType indexed = MyBatisJavaPropertyResolver.indexedType(type);
            if (indexed == null) {
                return null;
            }
            candidate = indexed;
            canonicalTypes.add(indexed.getCanonicalText());
        }
        return canonicalTypes.size() == 1 ? candidate : null;
    }

    private static @Nullable PsiType collectionIndexType(
            @NotNull XmlAttributeValue source,
            @NotNull State collection) {
        if (collection.status() != MyBatisOgnlSemanticStatus.FOUND
                || collection.types().isEmpty()) {
            return null;
        }
        for (PsiType type : collection.types()) {
            ProgressManager.checkCanceled();
            if (MyBatisJavaPropertyResolver.isDynamicMap(type)
                    || MyBatisJavaPropertyResolver.indexedType(type) == null) {
                return null;
            }
        }
        return PsiTypes.intType();
    }

    private static @NotNull MyBatisOgnlSemanticModel merge(
            @NotNull MyBatisOgnlParseResult parsed,
            @NotNull List<BranchAnalysis> analyses) {
        MyBatisOgnlSemanticResult root = combine(
                analyses.stream().map(BranchAnalysis::root).toList());
        Map<OccurrenceKey, List<State>> states = new LinkedHashMap<>();
        for (BranchAnalysis analysis : analyses) {
            ProgressManager.checkCanceled();
            for (Map.Entry<OccurrenceKey, State> entry : analysis.occurrences().entrySet()) {
                states.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }
        List<MyBatisOgnlOccurrence> occurrences = states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new MyBatisOgnlOccurrence(
                        entry.getKey().name(),
                        entry.getKey().range(),
                        entry.getKey().kind(),
                        combine(entry.getValue())))
                .toList();
        return new MyBatisOgnlSemanticModel(parsed, root, occurrences);
    }

    private static @NotNull MyBatisOgnlSemanticResult combine(
            @NotNull List<State> states) {
        if (states.isEmpty()) {
            return MyBatisOgnlSemanticResult.lifecycle(MyBatisOgnlSemanticStatus.UNKNOWN);
        }
        boolean allFound = states.stream()
                .allMatch(state -> state.status() == MyBatisOgnlSemanticStatus.FOUND);
        boolean allMissing = states.stream()
                .allMatch(state -> state.status() == MyBatisOgnlSemanticStatus.DEFINITE_MISSING);
        if (!allFound && !allMissing) {
            return MyBatisOgnlSemanticResult.lifecycle(MyBatisOgnlSemanticStatus.UNKNOWN);
        }
        if (allMissing) {
            Set<String> variants = new LinkedHashSet<>();
            states.forEach(state -> variants.addAll(state.variants()));
            return new MyBatisOgnlSemanticResult(
                    MyBatisOgnlSemanticStatus.DEFINITE_MISSING,
                    List.of(),
                    List.of(),
                    variants.stream().sorted().toList());
        }
        Set<String> seenTypes = new LinkedHashSet<>();
        List<PsiType> types = new ArrayList<>();
        Set<PsiElement> targets = new LinkedHashSet<>();
        Set<String> variants = new LinkedHashSet<>();
        for (State state : states) {
            ProgressManager.checkCanceled();
            for (PsiType type : state.types()) {
                if (seenTypes.add(type.getCanonicalText())) {
                    types.add(type);
                }
            }
            targets.addAll(state.targets());
            variants.addAll(state.variants());
        }
        return new MyBatisOgnlSemanticResult(
                MyBatisOgnlSemanticStatus.FOUND,
                types,
                stableTargets(targets),
                variants.stream().sorted().toList());
    }

    private static @NotNull List<PsiElement> stableTargets(@NotNull Set<PsiElement> targets) {
        List<PsiElement> sorted = new ArrayList<>(targets);
        sorted.sort(Comparator.comparing(MyBatisOgnlSemanticAnalyzer::targetKey));
        return List.copyOf(sorted);
    }

    private static @NotNull String targetKey(@NotNull PsiElement target) {
        String file = target.getContainingFile() == null
                ? ""
                : target.getContainingFile().getName();
        return file + ':' + target.getTextOffset() + ':' + target.getText();
    }

    private static @NotNull MyBatisOgnlSemanticModel lifecycle(
            @NotNull MyBatisOgnlParseResult parsed,
            @NotNull MyBatisOgnlSemanticStatus status) {
        return new MyBatisOgnlSemanticModel(
                parsed,
                MyBatisOgnlSemanticResult.lifecycle(status),
                List.of());
    }

    private static @Nullable XmlTag nearestStatement(@Nullable XmlTag tag) {
        XmlTag current = tag;
        while (current != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(current)) {
                return current;
            }
            current = current.getParentTag();
        }
        return null;
    }

    private static @Nullable XmlTag mapperRoot(@Nullable XmlTag statement) {
        XmlTag current = statement;
        while (current != null && current.getParentTag() != null) {
            ProgressManager.checkCanceled();
            current = current.getParentTag();
        }
        return current != null && MyBatisXmlModel.isMapperRoot(current) ? current : null;
    }

    private static @NotNull List<XmlTag> path(
            @NotNull XmlTag ancestor,
            @NotNull XmlTag descendant) {
        Deque<XmlTag> reversed = new ArrayDeque<>();
        XmlTag current = descendant;
        while (current != ancestor) {
            ProgressManager.checkCanceled();
            reversed.push(current);
            current = current.getParentTag();
            if (current == null) {
                return List.of();
            }
        }
        return List.copyOf(reversed);
    }

    private static @Nullable XmlAttributeValue attributeValue(
            @NotNull XmlTag tag,
            @NotNull String name) {
        XmlAttribute attribute = tag.getAttribute(name);
        return attribute == null || !name.equals(attribute.getName())
                ? null
                : attribute.getValueElement();
    }

    private static @Nullable String normalizedAttribute(
            @NotNull XmlTag tag,
            @NotNull String name) {
        XmlAttributeValue value = attributeValue(tag, name);
        if (value == null) {
            return null;
        }
        String normalized = value.getValue().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static @NotNull PsiType type(
            @NotNull PsiElement context,
            @NotNull String qualifiedName) {
        return JavaPsiFacade.getElementFactory(context.getProject())
                .createTypeByFQClassName(qualifiedName, context.getResolveScope());
    }

    private static @Nullable PsiType ognlPseudoPropertyType(
            @NotNull PsiType sourceType,
            @NotNull String propertyName) {
        if (sourceType instanceof PsiArrayType && "length".equals(propertyName)) {
            return PsiTypes.intType();
        }
        if (!isCollectionType(sourceType)) {
            return null;
        }
        return switch (propertyName) {
            case "size" -> PsiTypes.intType();
            case "isEmpty" -> PsiTypes.booleanType();
            default -> null;
        };
    }

    private static @NotNull List<String> ognlPseudoPropertyVariants(
            @NotNull PsiType sourceType) {
        if (sourceType instanceof PsiArrayType) {
            return List.of("length");
        }
        return isCollectionType(sourceType)
                ? List.of("isEmpty", "size")
                : List.of();
    }

    private static boolean isCollectionType(@NotNull PsiType sourceType) {
        if (!(sourceType instanceof PsiClassType classType)) {
            return false;
        }
        String rawType = classType.rawType().getCanonicalText();
        return "java.util.List".equals(rawType)
                || "java.util.Collection".equals(rawType)
                || InheritanceUtil.isInheritor(sourceType, "java.util.Collection");
    }

    private static @NotNull GlobalSearchScope resolveScope(
            @NotNull PsiElement context) {
        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        return module == null
                ? GlobalSearchScope.projectScope(context.getProject())
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
    }

    private static @NotNull String ognlText(@NotNull XmlAttributeValue source) {
        return decodeOgnl(source).text();
    }

    private static @NotNull DecodedOgnl decodeOgnl(
            @NotNull XmlAttributeValue source) {
        String raw = Objects.requireNonNullElse(source.getValue(), "");
        StringBuilder decoded = new StringBuilder(raw.length());
        List<Integer> decodedBoundaries = new ArrayList<>();
        decodedBoundaries.add(0);
        for (int offset = 0; offset < raw.length();) {
            ProgressManager.checkCanceled();
            if (raw.charAt(offset) != '&') {
                decoded.append(raw.charAt(offset++));
                decodedBoundaries.add(offset);
                continue;
            }
            int semicolon = raw.indexOf(';', offset + 1);
            if (semicolon < 0) {
                decoded.append(raw.charAt(offset++));
                decodedBoundaries.add(offset);
                continue;
            }
            String entity = raw.substring(offset + 1, semicolon);
            Integer codePoint = xmlEntityCodePoint(entity);
            if (codePoint == null) {
                int rawEnd = semicolon + 1;
                while (offset < rawEnd) {
                    decoded.append(raw.charAt(offset++));
                    decodedBoundaries.add(offset);
                }
            } else {
                int rawEnd = semicolon + 1;
                for (char unit : Character.toChars(codePoint)) {
                    decoded.append(unit);
                    decodedBoundaries.add(rawEnd);
                }
                offset = rawEnd;
            }
        }
        return new DecodedOgnl(
                decoded.toString(),
                decodedBoundaries.stream().mapToInt(Integer::intValue).toArray());
    }

    private static @NotNull MyBatisOgnlSemanticModel remapOccurrences(
            @NotNull MyBatisOgnlSemanticModel model,
            @NotNull DecodedOgnl decoded) {
        List<MyBatisOgnlOccurrence> remapped = model.occurrences().stream()
                .map(occurrence -> new MyBatisOgnlOccurrence(
                        occurrence.name(),
                        decoded.rawRange(occurrence.range()),
                        occurrence.kind(),
                        occurrence.result()))
                .toList();
        return new MyBatisOgnlSemanticModel(
                model.parseResult(),
                model.rootResult(),
                remapped);
    }

    private static @Nullable Integer xmlEntityCodePoint(@NotNull String entity) {
        return switch (entity) {
            case "lt" -> (int) '<';
            case "gt" -> (int) '>';
            case "amp" -> (int) '&';
            case "apos" -> (int) '\'';
            case "quot" -> (int) '"';
            default -> numericEntityCodePoint(entity);
        };
    }

    private static @Nullable Integer numericEntityCodePoint(@NotNull String entity) {
        try {
            int codePoint;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                codePoint = Integer.parseInt(entity.substring(2), 16);
            } else if (entity.startsWith("#")) {
                codePoint = Integer.parseInt(entity.substring(1));
            } else {
                return null;
            }
            return Character.isValidCodePoint(codePoint) ? codePoint : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record DecodedOgnl(
            @NotNull String text,
            @NotNull int[] decodedBoundaries) {
        private DecodedOgnl {
            decodedBoundaries = decodedBoundaries.clone();
            if (decodedBoundaries.length != text.length() + 1) {
                throw new IllegalArgumentException(MyBatisOgnlMessages.message(
                        "ognl.error.decoded.boundaries.mismatch"));
            }
        }

        private @NotNull MyBatisOgnlRange rawRange(
                @NotNull MyBatisOgnlRange decodedRange) {
            int start = decodedBoundaries[Math.min(
                    decodedRange.startOffset(),
                    decodedBoundaries.length - 1)];
            int end = decodedBoundaries[Math.min(
                    decodedRange.endOffset(),
                    decodedBoundaries.length - 1)];
            return new MyBatisOgnlRange(start, Math.max(start, end));
        }
    }

    private static final class Inferencer {
        private final XmlAttributeValue source;
        private final Branch branch;
        private final IdentityHashMap<MyBatisOgnlExpression, State> states =
                new IdentityHashMap<>();
        private final Map<OccurrenceKey, State> occurrences = new LinkedHashMap<>();

        private Inferencer(@NotNull XmlAttributeValue source, @NotNull Branch branch) {
            this.source = source;
            this.branch = branch;
        }

        private @NotNull BranchAnalysis analyze(@NotNull MyBatisOgnlExpression root) {
            infer(root);
            return new BranchAnalysis(states.get(root), Map.copyOf(occurrences));
        }

        private @NotNull State inferText(@Nullable String text) {
            if (text == null) {
                return State.unknown();
            }
            return infer(MyBatisOgnlParser.parse(text).root());
        }

        private @NotNull State infer(@NotNull MyBatisOgnlExpression root) {
            Deque<Visit> pending = new ArrayDeque<>();
            pending.push(new Visit(root, false));
            while (!pending.isEmpty()) {
                ProgressManager.checkCanceled();
                Visit visit = pending.pop();
                if (states.containsKey(visit.expression())) {
                    continue;
                }
                if (visit.expanded()) {
                    State state = inferNode(visit.expression());
                    states.put(visit.expression(), state);
                    addOccurrence(visit.expression(), state);
                    continue;
                }
                pending.push(new Visit(visit.expression(), true));
                List<MyBatisOgnlExpression> children = children(visit.expression());
                for (int index = children.size() - 1; index >= 0; index--) {
                    MyBatisOgnlExpression child = children.get(index);
                    if (!states.containsKey(child)) {
                        pending.push(new Visit(child, false));
                    }
                }
            }
            return states.getOrDefault(root, State.unknown());
        }

        private @NotNull State inferNode(@NotNull MyBatisOgnlExpression expression) {
            return switch (expression) {
                case MyBatisOgnlExpression.Literal literal -> literal(literal);
                case MyBatisOgnlExpression.Name name -> name(name);
                case MyBatisOgnlExpression.Unary unary -> unary(unary);
                case MyBatisOgnlExpression.Binary binary -> binary(binary);
                case MyBatisOgnlExpression.Conditional conditional -> conditional(conditional);
                case MyBatisOgnlExpression.Property property -> property(property);
                case MyBatisOgnlExpression.MethodCall method -> method(method);
                case MyBatisOgnlExpression.Index index -> index(index);
                case MyBatisOgnlExpression.StaticMember member -> staticMember(member);
                case MyBatisOgnlExpression.TypeLiteral literal -> typeLiteral(literal);
                case MyBatisOgnlExpression.ListLiteral list -> listLiteral(list);
                case MyBatisOgnlExpression.MapLiteral ignored -> State.found(
                        List.of(type(source, JAVA_UTIL_MAP)),
                        List.of());
                case MyBatisOgnlExpression.Group group -> state(group.expression());
                case MyBatisOgnlExpression.Error ignored -> State.unknown();
            };
        }

        private @NotNull State literal(@NotNull MyBatisOgnlExpression.Literal literal) {
            PsiType literalType = switch (literal.kind()) {
                case NULL -> PsiTypes.nullType();
                case BOOLEAN -> PsiTypes.booleanType();
                case INTEGER -> PsiTypes.intType();
                case DECIMAL -> PsiTypes.doubleType();
                case CHARACTER -> PsiTypes.charType();
                case STRING -> type(source, JAVA_LANG_STRING);
            };
            return State.found(List.of(literalType), List.of());
        }

        private @NotNull State name(@NotNull MyBatisOgnlExpression.Name name) {
            List<Binding> bindings = branch.find(name.name());
            List<String> variants = rootVariants();
            if (!bindings.isEmpty()) {
                boolean certain = bindings.stream().allMatch(Binding::certain);
                List<PsiType> types = bindings.stream()
                        .map(Binding::type)
                        .filter(Objects::nonNull)
                        .toList();
                List<PsiElement> targets = bindings.stream()
                        .filter(Binding::certain)
                        .map(Binding::target)
                        .filter(Objects::nonNull)
                        .toList();
                return certain && !types.isEmpty()
                        ? State.found(types, targets, variants)
                        : State.unknown(variants);
            }
            if (name.kind() == MyBatisOgnlNameKind.CONTEXT) {
                return State.unknown(variants);
            }
            PsiType directType = branch.directType();
            if (branch.dynamicRoot()) {
                return State.unknown(variants);
            }
            if (directType == null) {
                return State.missing(variants);
            }
            PsiType pseudoProperty = ognlPseudoPropertyType(directType, name.name());
            if (pseudoProperty != null) {
                return State.found(List.of(pseudoProperty), List.of(), variants);
            }
            MyBatisJavaPropertyResolution property = MyBatisJavaPropertyResolver.resolve(
                    directType,
                    name.name(),
                    MyBatisJavaPropertyAccess.READ);
            if (property.unknown()) {
                return State.unknown(variants);
            }
            if (property.targets().isEmpty()) {
                return State.missing(variants);
            }
            return State.found(property.types(), property.targets(), variants);
        }

        private @NotNull State unary(@NotNull MyBatisOgnlExpression.Unary unary) {
            if (unary.operator() == MyBatisOgnlOperator.NOT) {
                return State.found(List.of(PsiTypes.booleanType()), List.of());
            }
            State operand = state(unary.operand());
            return operand.status() == MyBatisOgnlSemanticStatus.FOUND
                    && operand.types().stream().allMatch(TypeConversionUtil::isNumericType)
                    ? State.found(operand.types(), List.of())
                    : State.unknown();
        }

        private @NotNull State binary(@NotNull MyBatisOgnlExpression.Binary binary) {
            if (BOOLEAN_OPERATORS.contains(binary.operator())) {
                return State.found(List.of(PsiTypes.booleanType()), List.of());
            }
            State left = state(binary.left());
            State right = state(binary.right());
            if (binary.operator() == MyBatisOgnlOperator.ADD
                    && (hasType(left, JAVA_LANG_STRING) || hasType(right, JAVA_LANG_STRING))) {
                return State.found(List.of(type(source, JAVA_LANG_STRING)), List.of());
            }
            if (left.status() == MyBatisOgnlSemanticStatus.FOUND
                    && right.status() == MyBatisOgnlSemanticStatus.FOUND
                    && left.types().stream().allMatch(TypeConversionUtil::isNumericType)
                    && right.types().stream().allMatch(TypeConversionUtil::isNumericType)) {
                return State.found(promoteNumbers(left.types(), right.types()), List.of());
            }
            return State.unknown();
        }

        private @NotNull State conditional(
                @NotNull MyBatisOgnlExpression.Conditional conditional) {
            State whenTrue = state(conditional.whenTrue());
            State whenFalse = state(conditional.whenFalse());
            if (whenTrue.status() != MyBatisOgnlSemanticStatus.FOUND
                    || whenFalse.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return State.unknown();
            }
            return State.found(
                    uniqueTypes(whenTrue.types(), whenFalse.types()),
                    List.of());
        }

        private @NotNull State property(@NotNull MyBatisOgnlExpression.Property property) {
            State target = state(property.target());
            List<String> variants = propertyVariants(target);
            if (target.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return State.unknown(variants);
            }
            List<PsiType> types = new ArrayList<>();
            List<PsiElement> targets = new ArrayList<>();
            boolean unknown = false;
            for (PsiType targetType : target.types()) {
                ProgressManager.checkCanceled();
                PsiType pseudoProperty = ognlPseudoPropertyType(
                        targetType,
                        property.name());
                if (pseudoProperty != null) {
                    types.add(pseudoProperty);
                    continue;
                }
                MyBatisJavaPropertyResolution resolution = MyBatisJavaPropertyResolver.resolve(
                        targetType,
                        property.name(),
                        MyBatisJavaPropertyAccess.READ);
                unknown |= resolution.unknown();
                types.addAll(resolution.types());
                targets.addAll(resolution.targets());
            }
            if (unknown) {
                return State.unknown(variants);
            }
            return types.isEmpty()
                    ? State.missing(variants)
                    : State.found(types, targets, variants);
        }

        private @NotNull State method(@NotNull MyBatisOgnlExpression.MethodCall method) {
            State target = method.target().map(this::state)
                    .orElseGet(() -> branch.directType() == null || branch.dynamicRoot()
                            ? State.unknown()
                            : State.found(List.of(branch.directType()), List.of()));
            List<String> variants = methodVariants(target);
            if (target.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return State.unknown(variants);
            }
            List<PsiMethod> methods = new ArrayList<>();
            List<PsiType> returnTypes = new ArrayList<>();
            for (PsiType targetType : target.types()) {
                ProgressManager.checkCanceled();
                MethodCandidates candidates = methods(
                        targetType,
                        method.name(),
                        method.arguments().size(),
                        false);
                if (candidates.unknown()) {
                    return State.unknown(variants);
                }
                methods.addAll(candidates.methods());
                returnTypes.addAll(candidates.returnTypes());
            }
            if (methods.isEmpty()) {
                return State.missing(variants);
            }
            return methods.size() == 1
                    ? State.found(returnTypes, List.copyOf(methods), variants)
                    : State.unknown(variants);
        }

        private @NotNull State index(@NotNull MyBatisOgnlExpression.Index index) {
            State target = state(index.target());
            if (target.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return State.unknown();
            }
            List<PsiType> types = new ArrayList<>();
            for (PsiType targetType : target.types()) {
                ProgressManager.checkCanceled();
                if (MyBatisJavaPropertyResolver.isDynamicMap(targetType)) {
                    return State.unknown();
                }
                PsiType indexed = MyBatisJavaPropertyResolver.indexedType(targetType);
                if (indexed == null) {
                    return State.missing(List.of());
                }
                types.add(indexed);
            }
            return State.found(types, List.of());
        }

        private @NotNull State staticMember(
                @NotNull MyBatisOgnlExpression.StaticMember member) {
            if (member.className().indexOf('.') < 0) {
                return State.unknown();
            }
            List<PsiClass> classes = exactClasses(member.className());
            if (classes.isEmpty()) {
                return State.missing(List.of());
            }
            if (classes.size() != 1) {
                return State.unknown();
            }
            PsiClass psiClass = classes.getFirst();
            occurrences.put(
                    new OccurrenceKey(
                            member.className(),
                            member.classRange(),
                            MyBatisOgnlSymbolKind.STATIC_CLASS),
                    State.found(
                            List.of(JavaPsiFacade.getElementFactory(source.getProject())
                                    .createType(psiClass)),
                            List.of(psiClass)));
            if (member.arguments().isEmpty()) {
                PsiField field = psiClass.findFieldByName(member.memberName(), true);
                if (field == null || !field.hasModifierProperty(PsiModifier.STATIC)) {
                    return State.missing(staticVariants(psiClass));
                }
                return State.found(
                        List.of(field.getType()),
                        List.of(field),
                        staticVariants(psiClass));
            }
            MethodCandidates candidates = methods(
                    JavaPsiFacade.getElementFactory(source.getProject()).createType(psiClass),
                    member.memberName(),
                    member.arguments().orElseThrow().size(),
                    true);
            if (candidates.methods().isEmpty()) {
                return candidates.unknown()
                        ? State.unknown(staticVariants(psiClass))
                        : State.missing(staticVariants(psiClass));
            }
            return candidates.methods().size() == 1
                    ? State.found(
                    candidates.returnTypes(),
                    List.copyOf(candidates.methods()),
                    staticVariants(psiClass))
                    : State.unknown(staticVariants(psiClass));
        }

        private @NotNull State typeLiteral(
                @NotNull MyBatisOgnlExpression.TypeLiteral literal) {
            if (literal.qualifiedName().indexOf('.') < 0) {
                return State.unknown();
            }
            List<PsiClass> classes = exactClasses(literal.qualifiedName());
            if (classes.isEmpty()) {
                return State.missing(List.of());
            }
            if (classes.size() != 1) {
                return State.unknown();
            }
            PsiClass psiClass = classes.getFirst();
            return State.found(
                    List.of(JavaPsiFacade.getElementFactory(source.getProject())
                            .createType(psiClass)),
                    List.of(psiClass));
        }

        private @NotNull List<PsiClass> exactClasses(@NotNull String qualifiedName) {
            return java.util.Arrays.stream(
                            JavaPsiFacade.getInstance(source.getProject()).findClasses(
                                    qualifiedName,
                                    resolveScope(source)))
                    .filter(PsiElement::isValid)
                    .filter(candidate -> qualifiedName.equals(candidate.getQualifiedName()))
                    .toList();
        }

        private @NotNull State listLiteral(
                @NotNull MyBatisOgnlExpression.ListLiteral list) {
            List<PsiType> elementTypes = new ArrayList<>();
            for (MyBatisOgnlExpression element : list.elements()) {
                ProgressManager.checkCanceled();
                State state = state(element);
                if (state.status() != MyBatisOgnlSemanticStatus.FOUND) {
                    return State.found(List.of(type(source, "java.util.List")), List.of());
                }
                elementTypes.addAll(state.types());
            }
            Map<String, PsiType> unique = new LinkedHashMap<>();
            elementTypes.forEach(elementType -> unique.putIfAbsent(
                    elementType.getCanonicalText(),
                    elementType));
            PsiType rawList = type(source, "java.util.List");
            if (unique.size() != 1 || !(rawList instanceof PsiClassType listType)) {
                return State.found(List.of(rawList), List.of());
            }
            PsiClass listClass = listType.resolve();
            PsiType typedList = listClass == null
                    ? rawList
                    : JavaPsiFacade.getElementFactory(source.getProject()).createType(
                    listClass,
                    unique.values().iterator().next());
            return State.found(List.of(typedList), List.of());
        }

        private void addOccurrence(
                @NotNull MyBatisOgnlExpression expression,
                @NotNull State state) {
            switch (expression) {
                case MyBatisOgnlExpression.Name name -> occurrences.put(
                        new OccurrenceKey(
                                name.name(),
                                name.range(),
                                name.kind() == MyBatisOgnlNameKind.CONTEXT
                                        ? MyBatisOgnlSymbolKind.CONTEXT_VARIABLE
                                        : MyBatisOgnlSymbolKind.ROOT),
                        state);
                case MyBatisOgnlExpression.Property property -> occurrences.put(
                        new OccurrenceKey(
                                property.name(),
                                property.nameRange(),
                                MyBatisOgnlSymbolKind.PROPERTY),
                        state);
                case MyBatisOgnlExpression.MethodCall method -> occurrences.put(
                        new OccurrenceKey(
                                method.name(),
                                method.nameRange(),
                                MyBatisOgnlSymbolKind.METHOD),
                        state);
                case MyBatisOgnlExpression.StaticMember member -> occurrences.put(
                        new OccurrenceKey(
                                member.memberName(),
                                member.memberRange(),
                                MyBatisOgnlSymbolKind.STATIC_MEMBER),
                        state);
                case MyBatisOgnlExpression.TypeLiteral literal -> occurrences.put(
                        new OccurrenceKey(
                                literal.qualifiedName(),
                                literal.nameRange(),
                                MyBatisOgnlSymbolKind.STATIC_CLASS),
                        state);
                default -> {
                    // 只有带名称范围的表达式会生成引用 occurrence。
                }
            }
        }

        private @NotNull State state(@NotNull MyBatisOgnlExpression expression) {
            return states.getOrDefault(expression, State.unknown());
        }

        private @NotNull List<String> rootVariants() {
            Set<String> variants = new LinkedHashSet<>(branch.names());
            if (branch.directType() != null && !branch.dynamicRoot()) {
                variants.addAll(MyBatisJavaPropertyResolver.variants(
                        branch.directType(),
                        MyBatisJavaPropertyAccess.READ));
                variants.addAll(ognlPseudoPropertyVariants(branch.directType()));
            }
            return variants.stream().sorted().toList();
        }

        private @NotNull List<String> propertyVariants(@NotNull State target) {
            if (target.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return List.of();
            }
            Set<String> variants = new LinkedHashSet<>();
            for (PsiType type : target.types()) {
                ProgressManager.checkCanceled();
                variants.addAll(MyBatisJavaPropertyResolver.variants(
                        type,
                        MyBatisJavaPropertyAccess.READ));
                variants.addAll(ognlPseudoPropertyVariants(type));
            }
            return variants.stream().sorted().toList();
        }

        private @NotNull List<String> methodVariants(@NotNull State target) {
            if (target.status() != MyBatisOgnlSemanticStatus.FOUND) {
                return List.of();
            }
            Set<String> variants = new LinkedHashSet<>();
            for (PsiType type : target.types()) {
                ProgressManager.checkCanceled();
                PsiClass psiClass = type instanceof PsiClassType classType
                        ? classType.resolve()
                        : null;
                if (psiClass == null) {
                    continue;
                }
                for (PsiMethod method : psiClass.getAllMethods()) {
                    if (!method.hasModifierProperty(PsiModifier.STATIC)) {
                        variants.add(method.getName());
                    }
                }
            }
            return variants.stream().sorted().toList();
        }

        private @NotNull List<String> staticVariants(@NotNull PsiClass psiClass) {
            Set<String> variants = new LinkedHashSet<>();
            for (PsiField field : psiClass.getAllFields()) {
                ProgressManager.checkCanceled();
                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                    variants.add(field.getName());
                }
            }
            for (PsiMethod method : psiClass.getAllMethods()) {
                ProgressManager.checkCanceled();
                if (method.hasModifierProperty(PsiModifier.STATIC)) {
                    variants.add(method.getName());
                }
            }
            return variants.stream().sorted().toList();
        }

        private @NotNull MethodCandidates methods(
                @NotNull PsiType type,
                @NotNull String name,
                int argumentCount,
                boolean staticOnly) {
            if (!(type instanceof PsiClassType classType)) {
                return MethodCandidates.missing();
            }
            PsiClassType.ClassResolveResult resolved = classType.resolveGenerics();
            PsiClass psiClass = resolved.getElement();
            if (psiClass == null) {
                return MethodCandidates.unknownResult();
            }
            Set<PsiMethod> methods = new LinkedHashSet<>();
            List<PsiType> returnTypes = new ArrayList<>();
            for (PsiMethod method : psiClass.getAllMethods()) {
                ProgressManager.checkCanceled();
                if (!name.equals(method.getName())
                        || method.getParameterList().getParametersCount() != argumentCount
                        || method.hasModifierProperty(PsiModifier.STATIC) != staticOnly) {
                    continue;
                }
                if (methods.add(method)) {
                    PsiType returnType = method.getReturnType();
                    if (returnType != null) {
                        PsiSubstitutor substitutor = memberSubstitutor(
                                psiClass,
                                resolved.getSubstitutor(),
                                method.getContainingClass());
                        PsiType substituted = substitutor.substitute(returnType);
                        returnTypes.add(substituted == null ? returnType : substituted);
                    }
                }
            }
            return new MethodCandidates(List.copyOf(methods), returnTypes, false);
        }
    }

    private static @NotNull PsiSubstitutor memberSubstitutor(
            @NotNull PsiClass sourceClass,
            @NotNull PsiSubstitutor sourceSubstitutor,
            @Nullable PsiClass declaringClass) {
        return declaringClass == null || declaringClass.equals(sourceClass)
                ? sourceSubstitutor
                : TypeConversionUtil.getSuperClassSubstitutor(
                        declaringClass,
                        sourceClass,
                        sourceSubstitutor);
    }

    private static boolean hasType(@NotNull State state, @NotNull String canonicalName) {
        return state.types().stream()
                .anyMatch(type -> canonicalName.equals(type.getCanonicalText()));
    }

    private static @NotNull List<PsiType> promoteNumbers(
            @NotNull List<PsiType> left,
            @NotNull List<PsiType> right) {
        List<PsiType> all = new ArrayList<>(left);
        all.addAll(right);
        if (all.stream().anyMatch(PsiTypes.doubleType()::equals)) {
            return List.of(PsiTypes.doubleType());
        }
        if (all.stream().anyMatch(PsiTypes.floatType()::equals)) {
            return List.of(PsiTypes.floatType());
        }
        if (all.stream().anyMatch(PsiTypes.longType()::equals)) {
            return List.of(PsiTypes.longType());
        }
        return List.of(PsiTypes.intType());
    }

    private static @NotNull List<PsiType> uniqueTypes(
            @NotNull List<PsiType> first,
            @NotNull List<PsiType> second) {
        Map<String, PsiType> types = new LinkedHashMap<>();
        first.forEach(type -> types.putIfAbsent(type.getCanonicalText(), type));
        second.forEach(type -> types.putIfAbsent(type.getCanonicalText(), type));
        return List.copyOf(types.values());
    }

    private static @NotNull List<MyBatisOgnlExpression> children(
            @NotNull MyBatisOgnlExpression expression) {
        List<MyBatisOgnlExpression> children = new ArrayList<>();
        switch (expression) {
            case MyBatisOgnlExpression.Unary unary -> children.add(unary.operand());
            case MyBatisOgnlExpression.Binary binary -> {
                children.add(binary.left());
                children.add(binary.right());
            }
            case MyBatisOgnlExpression.Conditional conditional -> {
                children.add(conditional.condition());
                children.add(conditional.whenTrue());
                children.add(conditional.whenFalse());
            }
            case MyBatisOgnlExpression.Property property -> children.add(property.target());
            case MyBatisOgnlExpression.MethodCall method -> {
                method.target().ifPresent(children::add);
                children.addAll(method.arguments());
            }
            case MyBatisOgnlExpression.Index index -> {
                children.add(index.target());
                children.add(index.index());
            }
            case MyBatisOgnlExpression.StaticMember member ->
                    member.arguments().ifPresent(children::addAll);
            case MyBatisOgnlExpression.TypeLiteral ignored -> {
                // 类型字面量没有子表达式。
            }
            case MyBatisOgnlExpression.ListLiteral list -> children.addAll(list.elements());
            case MyBatisOgnlExpression.MapLiteral map -> map.entries().forEach(entry -> {
                children.add(entry.key());
                children.add(entry.value());
            });
            case MyBatisOgnlExpression.Group group -> children.add(group.expression());
            default -> {
                // 叶子表达式没有子节点。
            }
        }
        return children;
    }

    private record Binding(
            @NotNull String name,
            @Nullable PsiType type,
            @Nullable PsiElement target,
            boolean certain) {
    }

    private static final class Branch {
        private final Map<String, List<Binding>> bindings = new LinkedHashMap<>();
        private final PsiType directType;
        private final boolean dynamicRoot;
        private final XmlTag statement;
        private final XmlTag hostTag;

        private Branch(
                @Nullable PsiType directType,
                boolean dynamicRoot,
                @NotNull XmlTag statement,
                @NotNull XmlTag hostTag) {
            this.directType = directType;
            this.dynamicRoot = dynamicRoot;
            this.statement = statement;
            this.hostTag = hostTag;
        }

        private void add(@NotNull Binding binding) {
            bindings.computeIfAbsent(binding.name(), ignored -> new ArrayList<>()).add(binding);
        }

        private void shadow(@NotNull Binding binding) {
            bindings.put(binding.name(), new ArrayList<>(List.of(binding)));
        }

        private @NotNull List<Binding> find(@NotNull String name) {
            return List.copyOf(bindings.getOrDefault(name, List.of()));
        }

        private @NotNull Set<String> names() {
            return Set.copyOf(bindings.keySet());
        }

        private @Nullable PsiType directType() {
            return directType;
        }

        private boolean dynamicRoot() {
            return dynamicRoot;
        }

        private @NotNull XmlTag statement() {
            return statement;
        }

        private @NotNull XmlTag hostTag() {
            return hostTag;
        }
    }

    private record State(
            @NotNull MyBatisOgnlSemanticStatus status,
            @NotNull List<PsiType> types,
            @NotNull List<PsiElement> targets,
            @NotNull List<String> variants) {
        private State {
            types = List.copyOf(types);
            targets = List.copyOf(targets);
            variants = List.copyOf(variants);
        }

        private static @NotNull State found(
                @NotNull List<PsiType> types,
                @NotNull List<PsiElement> targets) {
            return found(types, targets, List.of());
        }

        private static @NotNull State found(
                @NotNull List<PsiType> types,
                @NotNull List<PsiElement> targets,
                @NotNull List<String> variants) {
            return new State(MyBatisOgnlSemanticStatus.FOUND, types, targets, variants);
        }

        private static @NotNull State unknown() {
            return unknown(List.of());
        }

        private static @NotNull State unknown(@NotNull List<String> variants) {
            return new State(
                    MyBatisOgnlSemanticStatus.UNKNOWN,
                    List.of(),
                    List.of(),
                    variants);
        }

        private static @NotNull State missing(@NotNull List<String> variants) {
            return new State(
                    MyBatisOgnlSemanticStatus.DEFINITE_MISSING,
                    List.of(),
                    List.of(),
                    variants);
        }

        private @Nullable PsiType singleType() {
            return status == MyBatisOgnlSemanticStatus.FOUND && types.size() == 1
                    ? types.getFirst()
                    : null;
        }
    }

    private record Visit(
            @NotNull MyBatisOgnlExpression expression,
            boolean expanded) {
    }

    private record OccurrenceKey(
            @NotNull String name,
            @NotNull MyBatisOgnlRange range,
            @NotNull MyBatisOgnlSymbolKind kind) implements Comparable<OccurrenceKey> {
        @Override
        public int compareTo(@NotNull OccurrenceKey other) {
            int byStart = Integer.compare(range.startOffset(), other.range.startOffset());
            if (byStart != 0) {
                return byStart;
            }
            int byEnd = Integer.compare(range.endOffset(), other.range.endOffset());
            if (byEnd != 0) {
                return byEnd;
            }
            return kind.compareTo(other.kind);
        }
    }

    private record BranchAnalysis(
            @NotNull State root,
            @NotNull Map<OccurrenceKey, State> occurrences) {
    }

    private record MethodCandidates(
            @NotNull List<PsiMethod> methods,
            @NotNull List<PsiType> returnTypes,
            boolean unknown) {
        private static @NotNull MethodCandidates missing() {
            return new MethodCandidates(List.of(), List.of(), false);
        }

        private static @NotNull MethodCandidates unknownResult() {
            return new MethodCandidates(List.of(), List.of(), true);
        }
    }

    private static final class NonCacheableSemanticException extends RuntimeException {
        private final MyBatisOgnlSemanticModel model;

        private NonCacheableSemanticException(@NotNull MyBatisOgnlSemanticModel model) {
            this.model = model;
        }

        private @NotNull MyBatisOgnlSemanticModel model() {
            return model;
        }
    }
}
