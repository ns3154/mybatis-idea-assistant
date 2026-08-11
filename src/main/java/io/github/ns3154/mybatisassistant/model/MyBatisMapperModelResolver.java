package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.HierarchicalMethodSignature;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
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
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.index.MyBatisConfigurationIndex;
import io.github.ns3154.mybatisassistant.index.MyBatisConfigurationLocator;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolIndex;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MyBatisMapperModelResolver {
    private static final Key<CachedValue<MyBatisMapperModelResolution>> MODEL_CACHE_KEY =
            Key.create("mybatis.idea.assistant.mapper.model");
    private static final Key<ModelSnapshot> MODEL_SNAPSHOT_KEY =
            Key.create("mybatis.idea.assistant.mapper.model.snapshot");

    private MyBatisMapperModelResolver() {
    }

    /**
     * 解析 Java PSI Mapper 模型。调用方必须持有读锁；取消异常会直接向上传播。
     */
    public static @NotNull MyBatisMapperModelResolution resolve(@NotNull PsiElement source) {
        ProgressManager.checkCanceled();
        if (!source.isValid()) {
            return new MyBatisMapperModelResolution.SourceInvalid();
        }
        Project project = source.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisMapperModelResolution.SourceInvalid();
        }
        if (!(source instanceof PsiClass mapper)) {
            return new MyBatisMapperModelResolution.UnsupportedSource();
        }
        if (!mapper.isInterface() || mapper.isAnnotationType() || mapper.getQualifiedName() == null) {
            return new MyBatisMapperModelResolution.UnsupportedSource();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisMapperModelResolution.IndexNotReady();
        }

        try {
            MyBatisMapperModelResolution resolution = CachedValuesManager.getCachedValue(
                    mapper,
                    MODEL_CACHE_KEY,
                    () -> cachedModel(project, mapper));
            if (!isSourceUsable(project, mapper)) {
                return new MyBatisMapperModelResolution.SourceInvalid();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisMapperModelResolution.IndexNotReady();
            }
            return resolution;
        } catch (IndexNotReadyException ignored) {
            return new MyBatisMapperModelResolution.IndexNotReady();
        } catch (NonCacheableModelException exception) {
            return exception.resolution();
        }
    }

    private static @NotNull CachedValueProvider.Result<MyBatisMapperModelResolution> cachedModel(
            @NotNull Project project,
            @NotNull PsiClass mapper) {
        MyBatisMapperModelResolution resolution = reuseSemanticSnapshot(
                mapper,
                buildModel(project, mapper));
        if (resolution instanceof MyBatisMapperModelResolution.IndexNotReady
                || resolution instanceof MyBatisMapperModelResolution.SourceInvalid) {
            throw new NonCacheableModelException(resolution);
        }
        PsiFile sourceFile = mapper.getContainingFile();
        if (sourceFile == null || !sourceFile.isValid()) {
            throw new NonCacheableModelException(new MyBatisMapperModelResolution.SourceInvalid());
        }
        ModificationTracker sourceFileTracker = () -> sourceFile.isValid()
                ? sourceFile.getModificationStamp()
                : Long.MAX_VALUE;
        ModificationTracker indexTracker = () -> project.isDisposed()
                ? Long.MAX_VALUE
                : FileBasedIndex.getInstance().getIndexModificationStamp(
                        MyBatisXmlSymbolIndex.NAME,
                        project);
        ModificationTracker configurationIndexTracker = () -> project.isDisposed()
                ? Long.MAX_VALUE
                : FileBasedIndex.getInstance().getIndexModificationStamp(
                        MyBatisConfigurationIndex.NAME,
                        project);
        List<Object> dependencies = new ArrayList<>();
        dependencies.add(sourceFileTracker);
        collectDeclaringFileTrackers(mapper, dependencies);
        dependencies.add(MyBatisMapperScanRegistry.getInstance(project).getModificationTracker());
        dependencies.add(indexTracker);
        dependencies.add(configurationIndexTracker);
        dependencies.add(DumbService.getInstance(project).getModificationTracker());
        dependencies.add(ProjectRootModificationTracker.getInstance(project));
        return CachedValueProvider.Result.create(resolution, dependencies);
    }

    private static @NotNull MyBatisMapperModelResolution buildModel(
            @NotNull Project project,
            @NotNull PsiClass mapper) {
        ProgressManager.checkCanceled();
        if (!isSourceUsable(project, mapper)) {
            return new MyBatisMapperModelResolution.SourceInvalid();
        }
        String qualifiedName = mapper.getQualifiedName();
        if (qualifiedName == null) {
            return new MyBatisMapperModelResolution.UnsupportedSource();
        }

        List<MyBatisMapperEvidence> evidence = new ArrayList<>();
        GlobalSearchScope scope = mapper.getResolveScope();
        List<XmlTag> mapperRoots = MyBatisXmlSymbolLocator.findMapperRoots(
                project,
                qualifiedName,
                scope);
        if (!mapperRoots.isEmpty()) {
            evidence.add(new MyBatisMapperEvidence(
                    MyBatisMapperEvidenceKind.XML_NAMESPACE,
                    qualifiedName + "#sources=" + mapperRoots.size()));
        }
        if (MyBatisAnnotationModel.hasMapperAnnotation(mapper)) {
            evidence.add(new MyBatisMapperEvidence(
                    MyBatisMapperEvidenceKind.MAPPER_ANNOTATION,
                    MyBatisAnnotationModel.MAPPER_ANNOTATION));
        }
        evidence.addAll(MyBatisMapperScanRegistry.getInstance(project).findEvidence(mapper));
        collectConfigurationEvidence(project, qualifiedName, scope, evidence);
        if (evidence.isEmpty()) {
            return new MyBatisMapperModelResolution.NotMapper();
        }

        List<MyBatisMapperMethodModel> methods = collectMethods(mapper);
        evidence.sort(Comparator.comparing(item -> item.kind().name()));
        return new MyBatisMapperModelResolution.Found(
                new MyBatisMapperModel(qualifiedName, evidence, methods));
    }

    private static @NotNull List<MyBatisMapperMethodModel> collectMethods(@NotNull PsiClass mapper) {
        List<MyBatisMapperMethodModel> methods = new ArrayList<>();
        Set<String> stableSignatures = new LinkedHashSet<>();
        for (HierarchicalMethodSignature signature : mapper.getVisibleSignatures()) {
            ProgressManager.checkCanceled();
            PsiMethod method = signature.getMethod();
            PsiClass declaringClass = method.getContainingClass();
            if (declaringClass == null
                    || declaringClass.getQualifiedName() == null
                    || !method.hasModifierProperty(PsiModifier.ABSTRACT)
                    || method.hasModifierProperty(PsiModifier.STATIC)
                    || method.hasModifierProperty(PsiModifier.DEFAULT)
                    || method.getBody() != null) {
                continue;
            }
            MyBatisMapperMethodModel model = toMethodModel(
                    mapper,
                    method,
                    declaringClass,
                    signature.getSubstitutor());
            if (stableSignatures.add(model.stableSignature())) {
                methods.add(model);
            }
        }
        methods.sort(Comparator.comparing(MyBatisMapperMethodModel::stableSignature)
                .thenComparing(MyBatisMapperMethodModel::declaringType));
        return List.copyOf(methods);
    }

    private static void collectConfigurationEvidence(
            @NotNull Project project,
            @NotNull String mapperQualifiedName,
            @NotNull GlobalSearchScope scope,
            @NotNull List<MyBatisMapperEvidence> evidence) {
        List<XmlTag> classDeclarations = MyBatisConfigurationLocator.find(
                project,
                MyBatisConfigurationEntryKind.MAPPER_CLASS,
                mapperQualifiedName,
                scope);
        if (!classDeclarations.isEmpty()) {
            evidence.add(new MyBatisMapperEvidence(
                    MyBatisMapperEvidenceKind.MYBATIS_CONFIGURATION,
                    "mapper-class:" + mapperQualifiedName + "#sources=" + classDeclarations.size()));
        }
        for (XmlTag packageDeclaration : MyBatisConfigurationLocator.findAll(
                project,
                MyBatisConfigurationEntryKind.MAPPER_PACKAGE,
                scope)) {
            ProgressManager.checkCanceled();
            String packageName = packageDeclaration.getAttributeValue("name");
            if (packageName != null && mapperQualifiedName.startsWith(packageName.trim() + '.')) {
                evidence.add(new MyBatisMapperEvidence(
                        MyBatisMapperEvidenceKind.MYBATIS_CONFIGURATION,
                        "mapper-package:" + packageName.trim()));
            }
        }
    }

    private static @NotNull MyBatisMapperMethodModel toMethodModel(
            @NotNull PsiClass mapper,
            @NotNull PsiMethod method,
            @NotNull PsiClass declaringClass,
            @NotNull PsiSubstitutor substitutor) {
        List<MyBatisParameterModel> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            ProgressManager.checkCanceled();
            String explicitName = MyBatisAnnotationModel.explicitParameterName(parameter);
            PsiType substitutedType = substituted(substitutor, parameter.getType());
            parameters.add(new MyBatisParameterModel(
                    explicitName == null ? parameter.getName() : explicitName,
                    substitutedType.getCanonicalText(),
                    explicitName != null,
                    MyBatisEntityModelFactory.create(substitutedType, parameter)));
        }
        PsiType returnType = method.getReturnType();
        String canonicalReturnType = returnType == null
                ? "void"
                : substituted(substitutor, returnType).getCanonicalText();
        return new MyBatisMapperMethodModel(
                method.getName(),
                declaringClass.getQualifiedName(),
                canonicalReturnType,
                MyBatisEntityModelFactory.create(
                        returnType == null ? PsiTypes.voidType() : substituted(substitutor, returnType),
                        method),
                parameters,
                MyBatisAnnotationModel.statementSource(method),
                !mapper.isEquivalentTo(declaringClass));
    }

    private static @NotNull PsiType substituted(
            @NotNull PsiSubstitutor substitutor,
            @NotNull PsiType type) {
        PsiType substituted = substitutor.substitute(type);
        return substituted == null ? type : substituted;
    }

    private static boolean isSourceUsable(@NotNull Project project, @NotNull PsiClass mapper) {
        ProgressManager.checkCanceled();
        return !project.isDisposed() && project.isOpen() && mapper.isValid();
    }

    private static void collectDeclaringFileTrackers(
            @NotNull PsiClass mapper,
            @NotNull List<Object> dependencies) {
        Set<PsiFile> files = new LinkedHashSet<>();
        for (HierarchicalMethodSignature signature : mapper.getVisibleSignatures()) {
            ProgressManager.checkCanceled();
            PsiFile file = signature.getMethod().getContainingFile();
            if (file != null && file.isValid() && files.add(file)) {
                dependencies.add((ModificationTracker) () -> file.isValid()
                        ? file.getModificationStamp()
                        : Long.MAX_VALUE);
            }
        }
    }

    private static @NotNull MyBatisMapperModelResolution reuseSemanticSnapshot(
            @NotNull PsiClass mapper,
            @NotNull MyBatisMapperModelResolution resolution) {
        ModelSnapshot previous = mapper.getUserData(MODEL_SNAPSHOT_KEY);
        if (previous != null && previous.resolution().equals(resolution)) {
            return previous.resolution();
        }
        mapper.putUserData(MODEL_SNAPSHOT_KEY, new ModelSnapshot(resolution));
        return resolution;
    }

    private record ModelSnapshot(@NotNull MyBatisMapperModelResolution resolution) {
    }

    private static final class NonCacheableModelException extends RuntimeException {
        private final MyBatisMapperModelResolution resolution;

        private NonCacheableModelException(@NotNull MyBatisMapperModelResolution resolution) {
            super(null, null, false, false);
            this.resolution = resolution;
        }

        private @NotNull MyBatisMapperModelResolution resolution() {
            return resolution;
        }
    }
}
