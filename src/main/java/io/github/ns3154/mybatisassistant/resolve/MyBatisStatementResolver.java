package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.indexing.FileBasedIndex;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolIndex;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MyBatisStatementResolver {
    private static final Key<CachedValue<MyBatisStatementResolution>> RESOLUTION_CACHE_KEY =
            Key.create("mybatis.idea.assistant.statement.resolution");

    private MyBatisStatementResolver() {
    }

    /**
     * 解析 Java Mapper 方法到 XML statement。调用方必须持有读锁；取消异常会直接向上传播。
     */
    public static @NotNull MyBatisStatementResolution resolve(@NotNull PsiElement source) {
        return resolveInternal(source, IndexedMyBatisStatementLookup.INSTANCE, true);
    }

    private static boolean hasSupportedStructure(
            @NotNull PsiMethod method,
            @NotNull PsiClass mapperInterface) {
        ProgressManager.checkCanceled();
        return method.isValid()
                && mapperInterface.isValid()
                && mapperInterface.isInterface()
                && !mapperInterface.isAnnotationType()
                && mapperInterface.getQualifiedName() != null
                && method.hasModifierProperty(PsiModifier.ABSTRACT)
                && !method.hasModifierProperty(PsiModifier.STATIC)
                && !method.hasModifierProperty(PsiModifier.DEFAULT)
                && method.getBody() == null
                && mapperInterface.findMethodsByName(method.getName(), false).length == 1;
    }

    /**
     * 不使用缓存的测试入口，用于验证 PSI 过滤和索引查询预算。
     */
    static @NotNull MyBatisStatementResolution resolveUncached(
            @NotNull PsiElement source,
            @NotNull MyBatisStatementLookup lookup) {
        return resolveInternal(source, lookup, false);
    }

    private static @NotNull MyBatisStatementResolution resolveInternal(
            @NotNull PsiElement source,
            @NotNull MyBatisStatementLookup lookup,
            boolean useCache) {
        ProgressManager.checkCanceled();
        if (!source.isValid()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        Project project = source.getProject();
        if (project.isDisposed() || !project.isOpen()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        if (!(source instanceof PsiMethod method)) {
            return new MyBatisStatementResolution.UnsupportedSource();
        }

        PsiClass mapperInterface = method.getContainingClass();
        if (mapperInterface == null || !mapperInterface.isValid()) {
            return new MyBatisStatementResolution.SourceInvalid();
        }
        if (!hasSupportedStructure(method, mapperInterface)) {
            return new MyBatisStatementResolution.UnsupportedSource();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisStatementResolution.IndexNotReady();
        }

        try {
            if (MyBatisAnnotationModel.statementSource(method) != MyBatisStatementSourceKind.XML) {
                return new MyBatisStatementResolution.UnsupportedSource();
            }
            String namespace = mapperInterface.getQualifiedName();
            if (namespace == null) {
                return new MyBatisStatementResolution.UnsupportedSource();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisStatementResolution.IndexNotReady();
            }
            MyBatisStatementResolution resolution = useCache
                    ? resolveCached(project, method, namespace, method.getName(), lookup)
                    : resolveFromIndex(project, method, namespace, method.getName(), lookup)
                            .resolution();
            if (!isSourceUsable(project, method)) {
                return new MyBatisStatementResolution.SourceInvalid();
            }
            if (DumbService.isDumb(project)) {
                return new MyBatisStatementResolution.IndexNotReady();
            }
            return resolution;
        } catch (IndexNotReadyException ignored) {
            return new MyBatisStatementResolution.IndexNotReady();
        } catch (NonCacheableResolutionException exception) {
            return exception.resolution();
        }
    }

    private static @NotNull MyBatisStatementResolution resolveCached(
            @NotNull Project project,
            @NotNull PsiMethod source,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull MyBatisStatementLookup lookup) {
        return CachedValuesManager.getCachedValue(source, RESOLUTION_CACHE_KEY, () -> {
            ResolutionComputation computation =
                    resolveFromIndex(project, source, namespace, statementId, lookup);
            MyBatisStatementResolution resolution = computation.resolution();
            if (resolution instanceof MyBatisStatementResolution.IndexNotReady
                    || resolution instanceof MyBatisStatementResolution.SourceInvalid) {
                throw new NonCacheableResolutionException(resolution);
            }

            PsiModificationTracker psiModificationTracker =
                    PsiModificationTracker.getInstance(project);
            PsiFile sourceFile = source.getContainingFile();
            if (sourceFile == null || !sourceFile.isValid()) {
                throw new NonCacheableResolutionException(
                        new MyBatisStatementResolution.SourceInvalid());
            }
            ModificationTracker sourceFileModificationTracker = () -> sourceFile.isValid()
                    ? sourceFile.getModificationStamp()
                    : Long.MAX_VALUE;
            ModificationTracker indexModificationTracker = () -> project.isDisposed()
                    ? Long.MAX_VALUE
                    : FileBasedIndex.getInstance().getIndexModificationStamp(
                            MyBatisXmlSymbolIndex.NAME,
                            project);
            List<Object> dependencies = new ArrayList<>();
            dependencies.add(sourceFileModificationTracker);
            dependencies.add(psiModificationTracker.forLanguage(XMLLanguage.INSTANCE));
            dependencies.add(indexModificationTracker);
            dependencies.add(DumbService.getInstance(project).getModificationTracker());
            dependencies.add(ProjectRootModificationTracker.getInstance(project));
            if (computation.inheritanceConsulted()) {
                PsiClass mapperInterface = source.getContainingClass();
                if (mapperInterface != null) {
                    dependencies.add(MyBatisInheritedMapperLocator.modificationTracker(
                            mapperInterface));
                }
            }
            return CachedValueProvider.Result.create(resolution, dependencies);
        });
    }

    private static @NotNull ResolutionComputation resolveFromIndex(
            @NotNull Project project,
            @NotNull PsiMethod source,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull MyBatisStatementLookup lookup) {
        ProgressManager.checkCanceled();
        GlobalSearchScope scope = source.getResolveScope();
        List<XmlTag> tags = lookup.find(project, namespace, statementId, scope);
        if (!isSourceUsable(project, source)) {
            return new ResolutionComputation(
                    new MyBatisStatementResolution.SourceInvalid(),
                    false);
        }
        if (tags.isEmpty()) {
            boolean mapperXmlExists = lookup.hasMapperXml(project, namespace, scope);
            if (!isSourceUsable(project, source)) {
                return new ResolutionComputation(
                        new MyBatisStatementResolution.SourceInvalid(),
                        false);
            }
            if (mapperXmlExists) {
                return new ResolutionComputation(
                        new MyBatisStatementResolution.StatementMissing(namespace, statementId),
                        false);
            }
            PsiClass mapperInterface = source.getContainingClass();
            List<XmlTag> inheritedTargets = mapperInterface == null
                    ? List.of()
                    : findInheritedTargets(
                            project,
                            source,
                            mapperInterface,
                            statementId,
                            lookup);
            if (inheritedTargets.isEmpty()) {
                return new ResolutionComputation(
                        new MyBatisStatementResolution.NoMapperXml(namespace),
                        mapperInterface != null);
            }
            return new ResolutionComputation(
                    materializeTargets(
                            project,
                            source,
                            inheritedTargets,
                            new MyBatisStatementResolution.NoMapperXml(namespace)),
                    true);
        }

        return new ResolutionComputation(
                materializeTargets(
                        project,
                        source,
                        tags,
                        new MyBatisStatementResolution.StatementMissing(namespace, statementId)),
                false);
    }

    private static @NotNull List<XmlTag> findInheritedTargets(
            @NotNull Project project,
            @NotNull PsiMethod source,
            @NotNull PsiClass mapperInterface,
            @NotNull String statementId,
            @NotNull MyBatisStatementLookup lookup) {
        List<XmlTag> targets = new ArrayList<>();
        for (PsiClass inheritor : MyBatisInheritedMapperLocator.find(mapperInterface)) {
            ProgressManager.checkCanceled();
            String childNamespace = inheritor.getQualifiedName();
            if (childNamespace == null || !hasUniqueVisibleMethod(inheritor, statementId)) {
                continue;
            }
            targets.addAll(lookup.find(
                    project,
                    childNamespace,
                    statementId,
                    inheritor.getResolveScope()));
            if (!isSourceUsable(project, source)) {
                return List.of();
            }
        }
        targets.sort((left, right) -> {
            ProgressManager.checkCanceled();
            int byPath = stablePath(left).compareTo(stablePath(right));
            return byPath != 0
                    ? byPath
                    : Integer.compare(left.getTextOffset(), right.getTextOffset());
        });
        return List.copyOf(targets);
    }

    private static @NotNull String stablePath(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return "";
        }
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null ? file.getName() : virtualFile.getPath();
    }

    private static boolean hasUniqueVisibleMethod(
            @NotNull PsiClass mapperInterface,
            @NotNull String statementId) {
        Set<PsiMethod> visibleMethods = new LinkedHashSet<>();
        for (PsiMethod candidate : mapperInterface.findMethodsByName(statementId, true)) {
            ProgressManager.checkCanceled();
            PsiMethod visible = mapperInterface.findMethodBySignature(candidate, true);
            if (visible != null
                    && visible.hasModifierProperty(PsiModifier.ABSTRACT)
                    && !visible.hasModifierProperty(PsiModifier.STATIC)
                    && !visible.hasModifierProperty(PsiModifier.DEFAULT)
                    && visible.getBody() == null) {
                visibleMethods.add(visible);
            }
        }
        return visibleMethods.size() == 1;
    }

    private static @NotNull MyBatisStatementResolution materializeTargets(
            @NotNull Project project,
            @NotNull PsiMethod source,
            @NotNull List<XmlTag> tags,
            @NotNull MyBatisStatementResolution emptyResolution) {

        SmartPointerManager pointerManager = SmartPointerManager.getInstance(project);
        List<SmartPsiElementPointer<XmlTag>> targets = new ArrayList<>(tags.size());
        for (XmlTag tag : tags) {
            ProgressManager.checkCanceled();
            if (tag.isValid()) {
                targets.add(pointerManager.createSmartPsiElementPointer(tag));
            }
        }
        if (!isSourceUsable(project, source)) {
            return new MyBatisStatementResolution.SourceInvalid();
        }

        if (targets.isEmpty()) {
            return emptyResolution;
        }
        if (targets.size() == 1) {
            return new MyBatisStatementResolution.UniqueMatch(targets.getFirst());
        }
        return new MyBatisStatementResolution.MultipleMatches(targets);
    }

    private static boolean isSourceUsable(
            @NotNull Project project,
            @NotNull PsiMethod source) {
        ProgressManager.checkCanceled();
        return !project.isDisposed() && project.isOpen() && source.isValid();
    }

    private static final class NonCacheableResolutionException extends RuntimeException {
        private final MyBatisStatementResolution resolution;

        private NonCacheableResolutionException(@NotNull MyBatisStatementResolution resolution) {
            super(null, null, false, false);
            this.resolution = resolution;
        }

        private @NotNull MyBatisStatementResolution resolution() {
            return resolution;
        }
    }

    private record ResolutionComputation(
            @NotNull MyBatisStatementResolution resolution,
            boolean inheritanceConsulted) {
    }
}
