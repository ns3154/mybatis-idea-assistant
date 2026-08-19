package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record MyBatisJavaPropertyResolution(
        @NotNull List<PsiElement> targets,
        @NotNull List<PsiType> types,
        boolean unknown) {
    public MyBatisJavaPropertyResolution {
        targets = List.copyOf(targets);
        types = List.copyOf(types);
    }
}
