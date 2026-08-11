package io.github.ns3154.mybatisassistant.reference;

import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;

final class MyBatisReferenceRenameSupport {
    private MyBatisReferenceRenameSupport() {
    }

    static @NotNull PsiElement renameRange(
            @NotNull PsiReference reference,
            @NotNull String newName) {
        return ElementManipulators.handleContentChange(
                reference.getElement(),
                reference.getRangeInElement(),
                newName);
    }

    static @NotNull String propertyName(
            @NotNull PsiReference reference,
            @NotNull String newElementName) {
        PsiElement target = reference.resolve();
        if (target instanceof PsiMethod) {
            if (newElementName.startsWith("get") && newElementName.length() > 3) {
                return decapitalize(newElementName.substring(3));
            }
            if (newElementName.startsWith("set") && newElementName.length() > 3) {
                return decapitalize(newElementName.substring(3));
            }
            if (newElementName.startsWith("is") && newElementName.length() > 2) {
                return decapitalize(newElementName.substring(2));
            }
            // 方法不再是 JavaBean 访问器时，不能把 XML 属性名误写成普通方法名。
            return reference.getCanonicalText();
        }
        return newElementName;
    }

    private static @NotNull String decapitalize(@NotNull String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
