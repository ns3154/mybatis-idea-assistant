package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MyBatisInheritedMapperLocator {
    private static final Key<CachedValue<InheritedDirectory>> CACHE_KEY =
            Key.create("mybatis.idea.assistant.inherited.mapper.directory");

    private MyBatisInheritedMapperLocator() {
    }

    static @NotNull List<PsiClass> find(@NotNull PsiClass baseInterface) {
        InheritedDirectory directory = directory(baseInterface);
        List<PsiClass> result = new ArrayList<>();
        for (InheritedMapper mapper : directory.mappers()) {
            ProgressManager.checkCanceled();
            PsiClass psiClass = mapper.pointer().getElement();
            if (psiClass != null && psiClass.isValid() && psiClass.isInterface()) {
                result.add(psiClass);
            }
        }
        return List.copyOf(result);
    }

    static @NotNull ModificationTracker modificationTracker(@NotNull PsiClass baseInterface) {
        return () -> directory(baseInterface).fingerprint();
    }

    private static @NotNull InheritedDirectory directory(@NotNull PsiClass baseInterface) {
        Project project = baseInterface.getProject();
        if (!baseInterface.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)) {
            return InheritedDirectory.EMPTY;
        }
        return CachedValuesManager.getCachedValue(baseInterface, CACHE_KEY, () -> {
            InheritedDirectory directory = buildDirectory(baseInterface);
            return CachedValueProvider.Result.create(
                    directory,
                    PsiModificationTracker.getInstance(project).forLanguage(JavaLanguage.INSTANCE),
                    DumbService.getInstance(project).getModificationTracker(),
                    ProjectRootModificationTracker.getInstance(project));
        });
    }

    private static @NotNull InheritedDirectory buildDirectory(@NotNull PsiClass baseInterface) {
        Project project = baseInterface.getProject();
        SmartPointerManager pointerManager = SmartPointerManager.getInstance(project);
        List<PsiClass> inheritors = new ArrayList<>(ClassInheritorsSearch.search(
                baseInterface,
                GlobalSearchScope.projectScope(project),
                true).findAll());
        inheritors.removeIf(candidate -> !candidate.isValid()
                || !candidate.isInterface()
                || candidate.isAnnotationType()
                || candidate.getQualifiedName() == null);
        inheritors.sort(Comparator.comparing(MyBatisInheritedMapperLocator::stablePath)
                .thenComparing(candidate -> candidate.getQualifiedName() == null
                        ? ""
                        : candidate.getQualifiedName()));

        List<InheritedMapper> result = new ArrayList<>();
        long fingerprint = 1L;
        for (PsiClass inheritor : inheritors) {
            ProgressManager.checkCanceled();
            String qualifiedName = inheritor.getQualifiedName();
            if (qualifiedName == null) {
                continue;
            }
            String path = stablePath(inheritor);
            long modificationStamp = sourceModificationStamp(inheritor);
            fingerprint = 31L * fingerprint + qualifiedName.hashCode();
            fingerprint = 31L * fingerprint + path.hashCode();
            fingerprint = 31L * fingerprint + modificationStamp;
            result.add(new InheritedMapper(
                    pointerManager.createSmartPsiElementPointer(inheritor)));
        }
        return new InheritedDirectory(List.copyOf(result), fingerprint);
    }

    private static @NotNull String stablePath(@NotNull PsiClass psiClass) {
        PsiFile file = psiClass.getContainingFile();
        if (file == null) {
            return "";
        }
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null ? file.getName() : virtualFile.getPath();
    }

    private static long sourceModificationStamp(@NotNull PsiClass psiClass) {
        PsiFile file = psiClass.getContainingFile();
        return file == null || !file.isValid() ? Long.MAX_VALUE : file.getModificationStamp();
    }

    private record InheritedMapper(
            @NotNull SmartPsiElementPointer<PsiClass> pointer) {
    }

    private record InheritedDirectory(@NotNull List<InheritedMapper> mappers, long fingerprint) {
        private static final InheritedDirectory EMPTY = new InheritedDirectory(List.of(), 0L);
    }
}
