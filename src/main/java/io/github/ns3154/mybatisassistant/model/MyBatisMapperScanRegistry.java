package io.github.ns3154.mybatisassistant.model;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service(Service.Level.PROJECT)
public final class MyBatisMapperScanRegistry {
    private static final String MAPPER_SCAN = "org.mybatis.spring.annotation.MapperScan";
    private static final String MAPPER_SCANS = "org.mybatis.spring.annotation.MapperScans";
    private static final String NO_ANNOTATION_FILTER = "java.lang.annotation.Annotation";
    private static final String NO_MARKER_FILTER = "java.lang.Class";
    private final Project project;
    private final CachedValue<List<ScanRule>> rules;

    public MyBatisMapperScanRegistry(@NotNull Project project) {
        this.project = project;
        this.rules = CachedValuesManager.getManager(project).createCachedValue(() -> {
            List<ScanRule> loadedRules = loadRules();
            PsiModificationTracker tracker = PsiModificationTracker.getInstance(project);
            return CachedValueProvider.Result.create(
                    loadedRules,
                    tracker.forLanguage(JavaLanguage.INSTANCE),
                    ProjectRootModificationTracker.getInstance(project));
        });
    }

    public static @NotNull MyBatisMapperScanRegistry getInstance(@NotNull Project project) {
        return project.getService(MyBatisMapperScanRegistry.class);
    }

    public @NotNull List<MyBatisMapperEvidence> findEvidence(@NotNull PsiClass mapper) {
        ProgressManager.checkCanceled();
        String qualifiedName = mapper.getQualifiedName();
        if (qualifiedName == null) {
            return List.of();
        }
        List<MyBatisMapperEvidence> evidence = new ArrayList<>();
        for (ScanRule rule : rules.getValue()) {
            ProgressManager.checkCanceled();
            String matchedPackage = rule.matchedPackage(mapper, qualifiedName);
            if (matchedPackage != null) {
                evidence.add(new MyBatisMapperEvidence(
                        MyBatisMapperEvidenceKind.MAPPER_SCAN,
                        rule.sourceQualifiedName() + "#package=" + matchedPackage));
            }
        }
        return List.copyOf(evidence);
    }

    /**
     * 返回 MapperScan 规则的语义指纹。无关注释或方法体编辑不会改变该值。
     */
    public @NotNull ModificationTracker getModificationTracker() {
        return () -> rules.getValue().hashCode();
    }

    private @NotNull List<ScanRule> loadRules() {
        ProgressManager.checkCanceled();
        PsiClass annotationClass = JavaPsiFacade.getInstance(project).findClass(
                MAPPER_SCAN,
                GlobalSearchScope.allScope(project));
        if (annotationClass == null) {
            return List.of();
        }
        Set<ScanRule> result = new LinkedHashSet<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        for (PsiClass source : AnnotatedElementsSearch.searchPsiClasses(
                annotationClass,
                scope).findAll()) {
            ProgressManager.checkCanceled();
            PsiAnnotation annotation = source.getAnnotation(MAPPER_SCAN);
            if (annotation != null) {
                addRule(source, annotation, result);
            }
        }
        PsiClass scansAnnotation = JavaPsiFacade.getInstance(project).findClass(
                MAPPER_SCANS,
                GlobalSearchScope.allScope(project));
        if (scansAnnotation != null) {
            for (PsiClass source : AnnotatedElementsSearch.searchPsiClasses(
                    scansAnnotation,
                    scope).findAll()) {
                ProgressManager.checkCanceled();
                PsiAnnotation container = source.getAnnotation(MAPPER_SCANS);
                if (container == null) {
                    continue;
                }
                collectContainedRules(source, container.findAttributeValue("value"), result);
            }
        }
        return List.copyOf(result);
    }

    private void collectContainedRules(
            @NotNull PsiClass source,
            @Nullable PsiAnnotationMemberValue value,
            @NotNull Set<ScanRule> result) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                ProgressManager.checkCanceled();
                collectContainedRules(source, initializer, result);
            }
        } else if (value instanceof PsiAnnotation annotation
                && MAPPER_SCAN.equals(annotation.getQualifiedName())) {
            addRule(source, annotation, result);
        }
    }

    private void addRule(
            @NotNull PsiClass source,
            @NotNull PsiAnnotation annotation,
            @NotNull Set<ScanRule> result) {
        String sourceQualifiedName = source.getQualifiedName();
        VirtualFile sourceFile = source.getContainingFile().getVirtualFile();
        if (sourceQualifiedName == null || sourceFile == null) {
            return;
        }
        Set<String> packages = new LinkedHashSet<>();
        collectStrings(annotation.findDeclaredAttributeValue("value"), packages);
        collectStrings(annotation.findDeclaredAttributeValue("basePackages"), packages);
        collectClassPackages(annotation.findDeclaredAttributeValue("basePackageClasses"), packages);
        if (packages.isEmpty()) {
            String sourcePackage = packageName(sourceQualifiedName);
            if (!sourcePackage.isEmpty()) {
                packages.add(sourcePackage);
            }
        }
        if (packages.isEmpty()) {
            return;
        }
        PsiAnnotationMemberValue annotationFilter =
                annotation.findDeclaredAttributeValue("annotationClass");
        PsiAnnotationMemberValue markerFilter =
                annotation.findDeclaredAttributeValue("markerInterface");
        String annotationClass = classLiteralQualifiedName(annotationFilter);
        String markerInterface = classLiteralQualifiedName(markerFilter);
        boolean annotationFilterDeclared = annotationFilter != null
                && !isSentinelClassLiteral(
                        annotationFilter,
                        annotationClass,
                        NO_ANNOTATION_FILTER);
        boolean markerFilterDeclared = markerFilter != null
                && !isSentinelClassLiteral(
                        markerFilter,
                        markerInterface,
                        NO_MARKER_FILTER);
        result.add(new ScanRule(
                Set.copyOf(packages),
                annotationFilterDeclared ? annotationClass : null,
                annotationFilterDeclared,
                markerFilterDeclared ? markerInterface : null,
                markerFilterDeclared,
                sourceQualifiedName,
                sourceFile));
    }

    private static boolean isSentinelClassLiteral(
            @NotNull PsiAnnotationMemberValue value,
            @Nullable String resolvedQualifiedName,
            @NotNull String sentinelQualifiedName) {
        if (sentinelQualifiedName.equals(resolvedQualifiedName)) {
            return true;
        }
        return value instanceof PsiClassObjectAccessExpression classObject
                && sentinelQualifiedName.equals(classObject.getOperand().getText());
    }

    private void collectStrings(
            @Nullable PsiAnnotationMemberValue value,
            @NotNull Set<String> target) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                ProgressManager.checkCanceled();
                collectStrings(initializer, target);
            }
            return;
        }
        if (value == null) {
            return;
        }
        Object constant = JavaPsiFacade.getInstance(project)
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        if (constant instanceof String packageName && !packageName.isBlank()) {
            target.add(packageName.trim());
        }
    }

    private void collectClassPackages(
            @Nullable PsiAnnotationMemberValue value,
            @NotNull Set<String> target) {
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue initializer : array.getInitializers()) {
                ProgressManager.checkCanceled();
                collectClassPackages(initializer, target);
            }
            return;
        }
        String qualifiedName = classLiteralQualifiedName(value);
        if (qualifiedName != null) {
            String packageName = packageName(qualifiedName);
            if (!packageName.isEmpty()) {
                target.add(packageName);
            }
        }
    }

    private static @Nullable String classLiteralQualifiedName(@Nullable PsiAnnotationMemberValue value) {
        if (!(value instanceof PsiClassObjectAccessExpression classObject)) {
            return null;
        }
        PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(classObject.getOperand().getType());
        PsiJavaCodeReferenceElement reference =
                classObject.getOperand().getInnermostComponentReferenceElement();
        if (psiClass == null && reference != null && reference.resolve() instanceof PsiClass resolved) {
            psiClass = resolved;
        }
        if (psiClass == null) {
            psiClass = JavaPsiFacade.getInstance(value.getProject())
                    .getResolveHelper()
                    .resolveReferencedClass(classObject.getOperand().getText(), classObject);
        }
        return psiClass == null ? null : psiClass.getQualifiedName();
    }

    private static @NotNull String packageName(@NotNull String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? "" : qualifiedName.substring(0, separator);
    }

    private record ScanRule(
            @NotNull Set<String> packages,
            @Nullable String annotationClass,
            boolean annotationFilterDeclared,
            @Nullable String markerInterface,
            boolean markerFilterDeclared,
            @NotNull String sourceQualifiedName,
            @NotNull VirtualFile sourceFile) {
        private @Nullable String matchedPackage(
                @NotNull PsiClass mapper,
                @NotNull String mapperQualifiedName) {
            if (!mapper.getResolveScope().contains(sourceFile)) {
                return null;
            }
            if (!matchesFilter(mapper)) {
                return null;
            }
            for (String packageName : packages) {
                ProgressManager.checkCanceled();
                if (mapperQualifiedName.startsWith(packageName + '.')) {
                    return packageName;
                }
            }
            return null;
        }

        private boolean matchesFilter(@NotNull PsiClass mapper) {
            if (!annotationFilterDeclared && !markerFilterDeclared) {
                return true;
            }
            return annotationClass != null && mapper.hasAnnotation(annotationClass)
                    || markerInterface != null && InheritanceUtil.isInheritor(mapper, markerInterface);
        }
    }
}
