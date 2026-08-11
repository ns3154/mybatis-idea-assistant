package io.github.ns3154.mybatisassistant.reference;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.List;

record MyBatisParameterPathResolution(
        @NotNull Status status,
        @NotNull List<PsiElement> targets,
        @NotNull List<String> variants) {
    MyBatisParameterPathResolution {
        targets = List.copyOf(targets);
        variants = List.copyOf(variants);
    }

    enum Status {
        FOUND,
        DEFINITE_MISSING,
        UNKNOWN,
        INDEX_NOT_READY,
        SOURCE_INVALID
    }
}
