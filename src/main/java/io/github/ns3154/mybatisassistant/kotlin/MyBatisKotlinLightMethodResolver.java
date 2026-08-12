package io.github.ns3154.mybatisassistant.kotlin;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.LightClassUtil;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.util.List;
import java.util.Optional;

/**
 * 将一个 Kotlin 源函数保守映射为唯一 JVM light method。
 */
public final class MyBatisKotlinLightMethodResolver {
    private MyBatisKotlinLightMethodResolver() {
    }

    public static @NotNull Optional<PsiMethod> findSingle(
            @NotNull KtNamedFunction function) {
        ProgressManager.checkCanceled();
        Project project = function.getProject();
        if (!function.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)
                || function.getName() == null) {
            return Optional.empty();
        }
        List<PsiMethod> methods = LightClassUtil.INSTANCE
                .getLightClassMethods(function)
                .stream()
                .filter(PsiMethod::isValid)
                .filter(method -> function.getName().equals(method.getName()))
                .toList();
        return methods.size() == 1 ? Optional.of(methods.getFirst()) : Optional.empty();
    }
}
