package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 一个 Mapper 方法在 MyBatis XML 中可见的参数根上下文。
 */
public record MyBatisParameterContext(
        @NotNull PsiMethod method,
        @NotNull MyBatisParameterRootMode rootMode,
        @NotNull List<MyBatisParameterBinding> bindings,
        @Nullable PsiParameter directParameter,
        @Nullable PsiType directType,
        boolean dynamicMapRoot) {
    public MyBatisParameterContext {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rootMode, "rootMode");
        bindings = List.copyOf(bindings);
        if (rootMode == MyBatisParameterRootMode.DIRECT
                && (directParameter == null || directType == null)) {
            throw new IllegalArgumentException("单参数直接上下文必须包含参数和替换后的类型");
        }
        if (rootMode == MyBatisParameterRootMode.NAMED
                && (directParameter != null || directType != null || dynamicMapRoot)) {
            throw new IllegalArgumentException("具名参数上下文不能包含直接根对象");
        }
    }

    public @NotNull List<MyBatisParameterBinding> findBindings(@NotNull String name) {
        return bindings.stream()
                .filter(binding -> name.equals(binding.name()))
                .toList();
    }
}
