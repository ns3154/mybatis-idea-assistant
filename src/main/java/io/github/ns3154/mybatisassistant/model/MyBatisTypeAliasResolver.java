package io.github.ns3154.mybatisassistant.model;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.index.MyBatisBootConfigurationLocator;
import io.github.ns3154.mybatisassistant.index.MyBatisConfigurationLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MyBatisTypeAliasResolver {
    private static final String ALIAS_ANNOTATION = "org.apache.ibatis.type.Alias";
    private static final Key<CachedValue<AliasClassDirectory>> CLASS_NAMES_CACHE_KEY =
            Key.create("mybatis.idea.assistant.type.alias.class.names");
    private static final Map<String, String> BUILT_IN_ALIASES = Map.ofEntries(
            Map.entry("string", "java.lang.String"),
            Map.entry("byte", "java.lang.Byte"),
            Map.entry("char", "java.lang.Character"),
            Map.entry("character", "java.lang.Character"),
            Map.entry("long", "java.lang.Long"),
            Map.entry("short", "java.lang.Short"),
            Map.entry("int", "java.lang.Integer"),
            Map.entry("integer", "java.lang.Integer"),
            Map.entry("double", "java.lang.Double"),
            Map.entry("float", "java.lang.Float"),
            Map.entry("boolean", "java.lang.Boolean"),
            Map.entry("date", "java.util.Date"),
            Map.entry("decimal", "java.math.BigDecimal"),
            Map.entry("bigdecimal", "java.math.BigDecimal"),
            Map.entry("biginteger", "java.math.BigInteger"),
            Map.entry("object", "java.lang.Object"),
            Map.entry("map", "java.util.Map"),
            Map.entry("hashmap", "java.util.HashMap"),
            Map.entry("list", "java.util.List"),
            Map.entry("arraylist", "java.util.ArrayList"),
            Map.entry("collection", "java.util.Collection"),
            Map.entry("iterator", "java.util.Iterator"),
            Map.entry("_byte", "byte"),
            Map.entry("_char", "char"),
            Map.entry("_character", "char"),
            Map.entry("_long", "long"),
            Map.entry("_short", "short"),
            Map.entry("_int", "int"),
            Map.entry("_integer", "int"),
            Map.entry("_double", "double"),
            Map.entry("_float", "float"),
            Map.entry("_boolean", "boolean"));

    private MyBatisTypeAliasResolver() {
    }

    /**
     * 在调用元素的解析作用域内解析 MyBatis TypeAlias；取消异常会直接向上传播。
     */
    public static @NotNull MyBatisTypeAliasResolution resolve(
            @NotNull PsiElement context,
            @NotNull String alias) {
        ProgressManager.checkCanceled();
        if (!context.isValid()) {
            return new MyBatisTypeAliasResolution.SourceInvalid();
        }
        Project project = context.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisTypeAliasResolution.SourceInvalid();
        }
        String normalizedAlias = alias.trim().toLowerCase(Locale.ROOT);
        if (normalizedAlias.isEmpty()) {
            return new MyBatisTypeAliasResolution.Unresolved();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisTypeAliasResolution.IndexNotReady();
        }

        try {
            Set<String> targets = new LinkedHashSet<>();
            String builtIn = BUILT_IN_ALIASES.get(normalizedAlias);
            if (builtIn != null) {
                targets.add(builtIn);
            }
            GlobalSearchScope scope = context.getResolveScope();
            collectExplicitAliases(project, scope, normalizedAlias, targets);
            collectPackageAliases(context, project, scope, normalizedAlias, targets);
            if (!context.isValid() || project.isDisposed() || !project.isOpen()) {
                return new MyBatisTypeAliasResolution.SourceInvalid();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisTypeAliasResolution.IndexNotReady();
            }
            List<String> sorted = targets.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return new MyBatisTypeAliasResolution.Unresolved();
            }
            if (sorted.size() == 1) {
                return new MyBatisTypeAliasResolution.Unique(sorted.getFirst());
            }
            return new MyBatisTypeAliasResolution.Multiple(sorted);
        } catch (IndexNotReadyException ignored) {
            return new MyBatisTypeAliasResolution.IndexNotReady();
        }
    }

    private static void collectExplicitAliases(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String alias,
            @NotNull Set<String> targets) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        for (XmlTag declaration : MyBatisConfigurationLocator.find(
                project,
                MyBatisConfigurationEntryKind.TYPE_ALIAS,
                alias,
                scope)) {
            ProgressManager.checkCanceled();
            String targetType = declaration.getAttributeValue("type");
            if (targetType == null) {
                continue;
            }
            for (PsiClass psiClass : facade.findClasses(targetType.trim(), scope)) {
                ProgressManager.checkCanceled();
                if (psiClass.getQualifiedName() != null) {
                    targets.add(psiClass.getQualifiedName());
                }
            }
        }
    }

    private static void collectPackageAliases(
            @NotNull PsiElement context,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String alias,
            @NotNull Set<String> targets) {
        List<String> collectedPackages = MyBatisConfigurationLocator.findAll(
                        project,
                        MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE,
                        scope)
                .stream()
                .map(tag -> tag.getAttributeValue("name"))
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        collectedPackages.addAll(MyBatisBootConfigurationLocator.findAll(
                        project,
                        MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                        scope)
                .stream()
                .map(MyBatisBootConfigurationEntry::value)
                .toList());
        List<String> packages = collectedPackages.stream().distinct().toList();
        if (packages.isEmpty()) {
            return;
        }
        PsiShortNamesCache shortNames = PsiShortNamesCache.getInstance(project);
        AliasClassDirectory directory = aliasClassDirectory(context, scope);
        for (String className : directory.classNames().getOrDefault(
                alias,
                List.of())) {
            ProgressManager.checkCanceled();
            for (PsiClass candidate : shortNames.getClassesByName(className, scope)) {
                ProgressManager.checkCanceled();
                collectPackageCandidate(candidate, packages, alias, targets);
            }
        }
        for (String qualifiedName : directory.annotatedTypes().getOrDefault(alias, List.of())) {
            ProgressManager.checkCanceled();
            if (packages.stream().anyMatch(
                    packageName -> qualifiedName.startsWith(packageName + '.'))) {
                targets.add(qualifiedName);
            }
        }
    }

    private static @NotNull AliasClassDirectory aliasClassDirectory(
            @NotNull PsiElement context,
            @NotNull GlobalSearchScope scope) {
        PsiFile contextFile = context.getContainingFile();
        if (contextFile == null || !contextFile.isValid()) {
            return AliasClassDirectory.EMPTY;
        }
        Project project = context.getProject();
        return CachedValuesManager.getCachedValue(
                contextFile,
                CLASS_NAMES_CACHE_KEY,
                () -> {
                    Map<String, Set<String>> collected = new HashMap<>();
                    PsiShortNamesCache.getInstance(project).processAllClassNames(className -> {
                        ProgressManager.checkCanceled();
                        collected.computeIfAbsent(
                                className.toLowerCase(Locale.ROOT),
                                ignored -> new LinkedHashSet<>()).add(className);
                        return true;
                    }, scope, null);
                    Map<String, List<String>> immutable = new HashMap<>();
                    collected.forEach((key, names) -> {
                        List<String> sorted = names.stream().sorted().toList();
                        immutable.put(key, sorted);
                    });
                    Map<String, Set<String>> annotated = new HashMap<>();
                    PsiClass aliasAnnotation = JavaPsiFacade.getInstance(project).findClass(
                            ALIAS_ANNOTATION,
                            GlobalSearchScope.allScope(project));
                    if (aliasAnnotation != null) {
                        for (PsiClass candidate : AnnotatedElementsSearch.searchPsiClasses(
                                aliasAnnotation,
                                scope).findAll()) {
                            ProgressManager.checkCanceled();
                            String qualifiedName = candidate.getQualifiedName();
                            String effectiveAlias = annotationAlias(candidate);
                            if (qualifiedName != null && effectiveAlias != null) {
                                annotated.computeIfAbsent(
                                        effectiveAlias.toLowerCase(Locale.ROOT),
                                        ignored -> new LinkedHashSet<>()).add(qualifiedName);
                            }
                        }
                    }
                    Map<String, List<String>> immutableAnnotated = new HashMap<>();
                    annotated.forEach((key, names) -> immutableAnnotated.put(
                            key,
                            names.stream().sorted().toList()));
                    PsiModificationTracker tracker = PsiModificationTracker.getInstance(project);
                    return CachedValueProvider.Result.create(
                            new AliasClassDirectory(
                                    Collections.unmodifiableMap(immutable),
                                    Collections.unmodifiableMap(immutableAnnotated)),
                            tracker.forLanguage(JavaLanguage.INSTANCE),
                            ProjectRootModificationTracker.getInstance(project));
                });
    }

    private static void collectPackageCandidate(
            @NotNull PsiClass candidate,
            @NotNull List<String> packages,
            @NotNull String alias,
            @NotNull Set<String> targets) {
        String qualifiedName = candidate.getQualifiedName();
        if (qualifiedName == null || packages.stream().noneMatch(
                packageName -> qualifiedName.startsWith(packageName + '.'))) {
            return;
        }
        String effectiveAlias = annotationAlias(candidate);
        if (effectiveAlias == null) {
            effectiveAlias = candidate.getName();
        }
        if (effectiveAlias != null && alias.equals(effectiveAlias.toLowerCase(Locale.ROOT))) {
            targets.add(qualifiedName);
        }
    }

    private static @Nullable String annotationAlias(@NotNull PsiClass candidate) {
        PsiAnnotation annotation = candidate.getAnnotation(ALIAS_ANNOTATION);
        if (annotation == null) {
            return null;
        }
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value == null) {
            return null;
        }
        Object constant = JavaPsiFacade.getInstance(candidate.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        return constant instanceof String alias && !alias.isBlank() ? alias.trim() : null;
    }

    private record AliasClassDirectory(
            @NotNull Map<String, List<String>> classNames,
            @NotNull Map<String, List<String>> annotatedTypes) {
        private static final AliasClassDirectory EMPTY = new AliasClassDirectory(
                Map.of(),
                Map.of());
    }

}
