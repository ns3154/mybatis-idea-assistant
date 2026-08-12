package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * 一个 OGNL 子表达式的保守类型、导航目标和补全候选。
 */
public record MyBatisOgnlSemanticResult(
        @NotNull MyBatisOgnlSemanticStatus status,
        @NotNull List<PsiType> types,
        @NotNull List<PsiElement> targets,
        @NotNull List<String> variants) {
    public MyBatisOgnlSemanticResult {
        Objects.requireNonNull(status, "status");
        types = List.copyOf(types);
        targets = List.copyOf(targets);
        variants = List.copyOf(variants);
        if (status != MyBatisOgnlSemanticStatus.FOUND
                && (!types.isEmpty() || !targets.isEmpty())) {
            throw new IllegalArgumentException(MyBatisOgnlMessages.message(
                    "ognl.error.semantic.lifecycle.payload"));
        }
    }

    public static @NotNull MyBatisOgnlSemanticResult lifecycle(
            @NotNull MyBatisOgnlSemanticStatus status) {
        return new MyBatisOgnlSemanticResult(status, List.of(), List.of(), List.of());
    }
}
