package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class MyBatisMapperMethodResolver {
    private MyBatisMapperMethodResolver() {
    }

    /**
     * 按完整 namespace 和 statement id 查找当前接口直接声明的方法。调用方必须持有读锁。
     */
    public static @NotNull List<PsiMethod> find(
            @NotNull PsiElement context,
            @NotNull String namespace,
            @NotNull String statementId) {
        if (!context.isValid()) {
            return List.of();
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        GlobalSearchScope scope = module == null
                ? context.getResolveScope()
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
        return find(context.getProject(), namespace, statementId, scope);
    }

    public static @NotNull List<PsiMethod> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId) {
        return find(
                project,
                namespace,
                statementId,
                GlobalSearchScope.projectScope(project));
    }

    public static @NotNull List<PsiMethod> find(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull GlobalSearchScope scope) {
        if (project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)
                || namespace.isBlank()
                || statementId.isBlank()) {
            return List.of();
        }
        ProgressManager.checkCanceled();

        try {
            return findFromIndex(project, namespace, statementId, scope);
        } catch (IndexNotReadyException ignored) {
            // Dumb Mode 可能在预检查后开始；索引竞态按暂不可用安全降级。
            return List.of();
        }
    }

    private static @NotNull List<PsiMethod> findFromIndex(
            @NotNull Project project,
            @NotNull String namespace,
            @NotNull String statementId,
            @NotNull GlobalSearchScope scope) {
        PsiClass[] mapperClasses = JavaPsiFacade.getInstance(project).findClasses(
                namespace,
                scope);
        List<PsiMethod> targets = new ArrayList<>();
        for (PsiClass mapperClass : mapperClasses) {
            ProgressManager.checkCanceled();
            if (project.isDisposed() || !project.isOpen()) {
                return List.of();
            }
            if (!mapperClass.isValid()
                    || !mapperClass.isInterface()
                    || !namespace.equals(mapperClass.getQualifiedName())) {
                continue;
            }

            for (PsiMethod method : mapperClass.findMethodsByName(statementId, false)) {
                ProgressManager.checkCanceled();
                if (method.isValid()) {
                    targets.add(method);
                }
            }
        }

        ProgressManager.checkCanceled();
        targets.sort((left, right) -> {
            ProgressManager.checkCanceled();
            int byPath = sourcePath(left).compareTo(sourcePath(right));
            if (byPath != 0) {
                return byPath;
            }
            return Integer.compare(left.getTextOffset(), right.getTextOffset());
        });
        ProgressManager.checkCanceled();
        return List.copyOf(targets);
    }

    private static @NotNull String sourcePath(@NotNull PsiMethod method) {
        PsiFile containingFile = method.getContainingFile();
        if (containingFile == null) {
            return "";
        }
        VirtualFile virtualFile = containingFile.getVirtualFile();
        return virtualFile == null ? containingFile.getName() : virtualFile.getPath();
    }

}
