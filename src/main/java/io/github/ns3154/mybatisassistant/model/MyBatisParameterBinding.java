package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 一个 XML 表达式根名称与 Java Mapper 参数的精确对应关系。
 */
public record MyBatisParameterBinding(
        @NotNull String name,
        @NotNull PsiParameter parameter,
        @NotNull PsiType type,
        int javaIndex,
        int effectiveIndex,
        @NotNull MyBatisParameterBindingKind kind,
        @NotNull MyBatisParameterBindingCertainty certainty) {
    public MyBatisParameterBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(certainty, "certainty");
        if (name.isBlank() || javaIndex < 0 || effectiveIndex < 0) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.parameter.binding.invalid"));
        }
    }
}
