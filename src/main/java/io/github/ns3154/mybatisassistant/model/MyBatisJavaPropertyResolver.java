package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析 MyBatis 可读或可写 JavaBean 属性，并保留泛型替换后的属性类型。
 */
public final class MyBatisJavaPropertyResolver {
    private MyBatisJavaPropertyResolver() {
    }

    public static @NotNull MyBatisJavaPropertyResolution resolve(
            @Nullable PsiType sourceType,
            @NotNull String name,
            @NotNull MyBatisJavaPropertyAccess access) {
        ProgressManager.checkCanceled();
        if (sourceType == null || isDynamicMap(sourceType)) {
            return new MyBatisJavaPropertyResolution(List.of(), List.of(), true);
        }
        if (!(sourceType instanceof PsiClassType classType)) {
            return new MyBatisJavaPropertyResolution(List.of(), List.of(), false);
        }
        PsiClassType.ClassResolveResult classResult = classType.resolveGenerics();
        PsiClass psiClass = classResult.getElement();
        if (psiClass == null || psiClass.getQualifiedName() == null) {
            return new MyBatisJavaPropertyResolution(List.of(), List.of(), true);
        }
        Set<PsiElement> targets = new LinkedHashSet<>();
        List<PsiType> types = new ArrayList<>();
        for (PsiMethod method : psiClass.getAllMethods()) {
            ProgressManager.checkCanceled();
            PsiType propertyType = propertyMethodType(method, name, access);
            if (propertyType == null) {
                continue;
            }
            targets.add(method);
            types.add(substituteMemberType(
                    psiClass,
                    classResult.getSubstitutor(),
                    method.getContainingClass(),
                    propertyType));
        }
        if (targets.isEmpty()) {
            PsiField field = psiClass.findFieldByName(name, true);
            if (field != null) {
                targets.add(field);
                types.add(substituteMemberType(
                        psiClass,
                        classResult.getSubstitutor(),
                        field.getContainingClass(),
                        field.getType()));
            }
        }
        return new MyBatisJavaPropertyResolution(List.copyOf(targets), List.copyOf(types), false);
    }

    public static @NotNull List<String> variants(
            @Nullable PsiType sourceType,
            @NotNull MyBatisJavaPropertyAccess access) {
        ProgressManager.checkCanceled();
        if (!(sourceType instanceof PsiClassType classType) || isDynamicMap(sourceType)) {
            return List.of();
        }
        PsiClass psiClass = classType.resolve();
        if (psiClass == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (PsiField field : psiClass.getAllFields()) {
            ProgressManager.checkCanceled();
            names.add(field.getName());
        }
        for (PsiMethod method : psiClass.getAllMethods()) {
            ProgressManager.checkCanceled();
            String property = propertyMethodName(method, access);
            if (property != null) {
                names.add(property);
            }
        }
        return List.copyOf(names);
    }

    public static @Nullable PsiType indexedType(@NotNull PsiType sourceType) {
        ProgressManager.checkCanceled();
        if (sourceType instanceof PsiArrayType arrayType) {
            return arrayType.getComponentType();
        }
        if (!(sourceType instanceof PsiClassType classType)) {
            return null;
        }
        String rawType = classType.rawType().getCanonicalText();
        if (!("java.util.List".equals(rawType)
                || "java.util.Collection".equals(rawType)
                || "java.lang.Iterable".equals(rawType)
                || InheritanceUtil.isInheritor(sourceType, "java.util.Collection")
                || InheritanceUtil.isInheritor(sourceType, "java.lang.Iterable"))) {
            return null;
        }
        PsiType[] parameters = classType.getParameters();
        return parameters.length == 1 ? parameters[0] : null;
    }

    public static boolean isDynamicMap(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType classType)) {
            return false;
        }
        if ("java.util.Map".equals(classType.rawType().getCanonicalText())) {
            return true;
        }
        return InheritanceUtil.isInheritor(type, "java.util.Map");
    }

    private static @Nullable PsiType propertyMethodType(
            @NotNull PsiMethod method,
            @NotNull String propertyName,
            @NotNull MyBatisJavaPropertyAccess access) {
        String resolvedName = propertyMethodName(method, access);
        if (!propertyName.equals(resolvedName)) {
            return null;
        }
        return access == MyBatisJavaPropertyAccess.READ
                ? method.getReturnType()
                : method.getParameterList().getParameter(0).getType();
    }

    private static @Nullable String propertyMethodName(
            @NotNull PsiMethod method,
            @NotNull MyBatisJavaPropertyAccess access) {
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return null;
        }
        String name = method.getName();
        if (access == MyBatisJavaPropertyAccess.READ) {
            if (method.getParameterList().getParametersCount() != 0
                    || PsiTypes.voidType().equals(method.getReturnType())
                    || "getClass".equals(name)) {
                return null;
            }
            if (name.startsWith("get") && name.length() > 3) {
                return decapitalize(name.substring(3));
            }
            if (name.startsWith("is") && name.length() > 2) {
                return decapitalize(name.substring(2));
            }
            return null;
        }
        return name.startsWith("set")
                && name.length() > 3
                && method.getParameterList().getParametersCount() == 1
                ? decapitalize(name.substring(3))
                : null;
    }

    private static @NotNull PsiType substituteMemberType(
            @NotNull PsiClass sourceClass,
            @NotNull PsiSubstitutor sourceSubstitutor,
            @Nullable PsiClass declaringClass,
            @NotNull PsiType memberType) {
        PsiSubstitutor substitutor = declaringClass == null || declaringClass.equals(sourceClass)
                ? sourceSubstitutor
                : TypeConversionUtil.getSuperClassSubstitutor(
                        declaringClass,
                        sourceClass,
                        sourceSubstitutor);
        PsiType substituted = substitutor.substitute(memberType);
        return substituted == null ? memberType : substituted;
    }

    private static @NotNull String decapitalize(@NotNull String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
