package io.github.ns3154.mybatisassistant.resolve;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MyBatisProviderMethodResolver {
    private static final String PROVIDER_METHOD_RESOLVER =
            "org.apache.ibatis.builder.annotation.ProviderMethodResolver";

    private MyBatisProviderMethodResolver() {
    }

    public static @NotNull List<PsiMethod> find(@NotNull PsiMethod mapperMethod) {
        ProgressManager.checkCanceled();
        if (!isSourceUsable(mapperMethod)) {
            return List.of();
        }
        try {
            Set<PsiMethod> targets = new LinkedHashSet<>();
            for (PsiAnnotation annotation : MyBatisAnnotationModel.statementAnnotations(
                    mapperMethod,
                    MyBatisStatementSourceKind.PROVIDER)) {
                ProgressManager.checkCanceled();
                targets.addAll(find(annotation));
            }
            return sorted(targets);
        } catch (IndexNotReadyException ignored) {
            return List.of();
        }
    }

    public static @NotNull List<PsiMethod> find(@NotNull PsiAnnotation providerAnnotation) {
        ProgressManager.checkCanceled();
        Project project = providerAnnotation.getProject();
        if (!providerAnnotation.isValid()
                || project.isDisposed()
                || !project.isOpen()
                || DumbService.isDumb(project)) {
            return List.of();
        }
        try {
            if (!MyBatisAnnotationModel.isProviderAnnotation(providerAnnotation)) {
                return List.of();
            }
            PsiClass providerClass = providerClass(providerAnnotation);
            String methodName = providerMethodSelection(providerAnnotation).explicitName();
            if (providerClass == null || methodName == null) {
                return List.of();
            }
            return findNamedMethods(providerClass, methodName);
        } catch (IndexNotReadyException ignored) {
            return List.of();
        }
    }

    public static @NotNull List<PsiElement> findNavigationTargets(
            @NotNull PsiMethod mapperMethod) {
        ProgressManager.checkCanceled();
        if (!isSourceUsable(mapperMethod)) {
            return List.of();
        }
        try {
            Set<PsiElement> targets = new LinkedHashSet<>();
            for (PsiAnnotation annotation : MyBatisAnnotationModel.statementAnnotations(
                    mapperMethod,
                    MyBatisStatementSourceKind.PROVIDER)) {
                ProgressManager.checkCanceled();
                PsiClass providerClass = providerClass(annotation);
                if (providerClass == null) {
                    continue;
                }
                ProviderMethodSelection selection = providerMethodSelection(annotation);
                String explicitMethodName = selection.explicitName();
                if (explicitMethodName != null) {
                    List<PsiMethod> methods = findRuntimeMethods(
                            providerClass,
                            explicitMethodName);
                    targets.addAll(methods.isEmpty() ? List.of(providerClass) : methods);
                    continue;
                }
                if (!selection.useDefault()) {
                    targets.add(providerClass);
                    continue;
                }
                if (implementsDynamicResolver(providerClass)) {
                    targets.add(providerClass);
                    continue;
                }
                List<PsiMethod> fallbackMethods = findRuntimeMethods(
                        providerClass,
                        "provideSql");
                targets.addAll(fallbackMethods.isEmpty()
                        ? List.of(providerClass)
                        : fallbackMethods);
            }
            return sortedElements(targets);
        } catch (IndexNotReadyException ignored) {
            return List.of();
        }
    }

    private static @Nullable PsiClass providerClass(@NotNull PsiAnnotation annotation) {
        PsiClass typeClass = classLiteralClass(
                annotation.findDeclaredAttributeValue("type"));
        PsiClass valueClass = classLiteralClass(
                annotation.findDeclaredAttributeValue("value"));
        if (typeClass != null && valueClass != null && !typeClass.equals(valueClass)) {
            return null;
        }
        return valueClass == null ? typeClass : valueClass;
    }

    private static @Nullable PsiClass classLiteralClass(
            @Nullable PsiAnnotationMemberValue classValue) {
        if (!(classValue instanceof PsiClassObjectAccessExpression classLiteral)
                || !(classLiteral.getOperand().getType() instanceof PsiClassType classType)) {
            return null;
        }
        return classType.resolve();
    }

    private static @NotNull ProviderMethodSelection providerMethodSelection(
            @NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("method");
        if (value == null) {
            return new ProviderMethodSelection(null, true);
        }
        Object constant = JavaPsiFacade.getInstance(annotation.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        if (!(constant instanceof String methodName)) {
            return new ProviderMethodSelection(null, false);
        }
        if (methodName.isBlank()) {
            return new ProviderMethodSelection(null, true);
        }
        String normalized = methodName.trim();
        String explicitName = JavaPsiFacade.getInstance(annotation.getProject())
                .getNameHelper()
                .isIdentifier(normalized)
                ? normalized
                : null;
        return new ProviderMethodSelection(explicitName, false);
    }

    private static @NotNull List<PsiMethod> findNamedMethods(
            @NotNull PsiClass providerClass,
            @NotNull String methodName) {
        Set<PsiMethod> targets = new LinkedHashSet<>();
        for (PsiMethod candidate : providerClass.findMethodsByName(methodName, true)) {
            ProgressManager.checkCanceled();
            PsiMethod visible = providerClass.findMethodBySignature(candidate, true);
            if (visible != null
                    && visible.isValid()
                    && !visible.isConstructor()
                    && !visible.hasModifierProperty(PsiModifier.ABSTRACT)) {
                targets.add(visible);
            }
        }
        return sorted(targets);
    }

    private static @NotNull List<PsiMethod> findRuntimeMethods(
            @NotNull PsiClass providerClass,
            @NotNull String methodName) {
        Set<PsiMethod> targets = new LinkedHashSet<>();
        for (PsiMethod method : findNamedMethods(providerClass, methodName)) {
            ProgressManager.checkCanceled();
            if (method.hasModifierProperty(PsiModifier.PUBLIC)
                    && returnsCharSequence(method)) {
                targets.add(method);
            }
        }
        return sorted(targets);
    }

    private static boolean returnsCharSequence(@NotNull PsiMethod method) {
        PsiType returnType = method.getReturnType();
        if (returnType == null) {
            return false;
        }
        PsiClass returnClass = PsiUtil.resolveClassInClassTypeOnly(returnType);
        if (returnClass != null) {
            return CommonClassNames.JAVA_LANG_CHAR_SEQUENCE.equals(
                    returnClass.getQualifiedName())
                    || InheritanceUtil.isInheritor(
                            returnClass,
                            CommonClassNames.JAVA_LANG_CHAR_SEQUENCE);
        }
        return switch (returnType.getCanonicalText()) {
            case "String", "java.lang.String",
                    "StringBuilder", "java.lang.StringBuilder",
                    "StringBuffer", "java.lang.StringBuffer",
                    "CharSequence", "java.lang.CharSequence" -> true;
            default -> false;
        };
    }

    private static boolean implementsDynamicResolver(@NotNull PsiClass providerClass) {
        PsiClass resolver = JavaPsiFacade.getInstance(providerClass.getProject()).findClass(
                PROVIDER_METHOD_RESOLVER,
                providerClass.getResolveScope());
        return resolver != null
                && InheritanceUtil.isInheritorOrSelf(providerClass, resolver, true);
    }

    private static @NotNull List<PsiMethod> sorted(@NotNull Set<PsiMethod> methods) {
        List<PsiMethod> targets = new ArrayList<>(methods);
        targets.sort(Comparator.comparing(MyBatisProviderMethodResolver::sourcePath)
                .thenComparingInt(PsiElement::getTextOffset));
        return List.copyOf(targets);
    }

    private static @NotNull List<PsiElement> sortedElements(
            @NotNull Set<PsiElement> elements) {
        List<PsiElement> targets = new ArrayList<>(elements);
        targets.sort(Comparator.comparing(MyBatisProviderMethodResolver::sourcePath)
                .thenComparingInt(PsiElement::getTextOffset));
        return List.copyOf(targets);
    }

    private static boolean isSourceUsable(@NotNull PsiMethod method) {
        Project project = method.getProject();
        return method.isValid()
                && !project.isDisposed()
                && project.isOpen()
                && !DumbService.isDumb(project);
    }

    private static @NotNull String sourcePath(@NotNull PsiElement element) {
        ProgressManager.checkCanceled();
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return "";
        }
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null ? file.getName() : virtualFile.getPath();
    }

    private record ProviderMethodSelection(
            @Nullable String explicitName,
            boolean useDefault) {
    }
}
