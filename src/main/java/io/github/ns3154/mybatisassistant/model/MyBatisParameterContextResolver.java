package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.HierarchicalMethodSignature;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 MyBatis {@code ParamNameResolver} 语义建立 XML 参数根上下文。
 */
public final class MyBatisParameterContextResolver {
    private static final String ROW_BOUNDS = "org.apache.ibatis.session.RowBounds";
    private static final String RESULT_HANDLER = "org.apache.ibatis.session.ResultHandler";

    private MyBatisParameterContextResolver() {
    }

    public static @NotNull MyBatisParameterContextResolution resolve(
            @NotNull PsiClass mapper,
            @NotNull PsiMethod method) {
        ProgressManager.checkCanceled();
        Project project = mapper.getProject();
        if (!mapper.isValid()
                || !method.isValid()
                || project.isDisposed()
                || !project.isOpen()) {
            return new MyBatisParameterContextResolution.SourceInvalid();
        }
        if (!mapper.isInterface()
                || mapper.isAnnotationType()
                || !method.hasModifierProperty(PsiModifier.ABSTRACT)
                || method.hasModifierProperty(PsiModifier.STATIC)
                || method.hasModifierProperty(PsiModifier.DEFAULT)
                || method.getBody() != null) {
            return new MyBatisParameterContextResolution.UnsupportedSource();
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisParameterContextResolution.IndexNotReady();
        }
        try {
            PsiSubstitutor substitutor = findSubstitutor(mapper, method);
            if (substitutor == null) {
                return new MyBatisParameterContextResolution.UnsupportedSource();
            }
            MyBatisParameterContext context = buildContext(method, substitutor);
            if (!mapper.isValid() || !method.isValid() || project.isDisposed() || !project.isOpen()) {
                return new MyBatisParameterContextResolution.SourceInvalid();
            }
            return new MyBatisParameterContextResolution.Found(context);
        } catch (IndexNotReadyException ignored) {
            return new MyBatisParameterContextResolution.IndexNotReady();
        }
    }

    private static @Nullable PsiSubstitutor findSubstitutor(
            @NotNull PsiClass mapper,
            @NotNull PsiMethod method) {
        for (HierarchicalMethodSignature signature : mapper.getVisibleSignatures()) {
            ProgressManager.checkCanceled();
            if (mapper.getManager().areElementsEquivalent(signature.getMethod(), method)) {
                return signature.getSubstitutor();
            }
        }
        return null;
    }

    private static @NotNull MyBatisParameterContext buildContext(
            @NotNull PsiMethod method,
            @NotNull PsiSubstitutor substitutor) {
        List<EffectiveParameter> parameters = new ArrayList<>();
        PsiParameter[] declaredParameters = method.getParameterList().getParameters();
        for (int javaIndex = 0; javaIndex < declaredParameters.length; javaIndex++) {
            ProgressManager.checkCanceled();
            PsiParameter parameter = declaredParameters[javaIndex];
            PsiType type = substituted(substitutor, parameter.getType());
            if (!isSpecialParameter(type)) {
                parameters.add(new EffectiveParameter(
                        parameter,
                        type,
                        javaIndex,
                        parameters.size(),
                        MyBatisAnnotationModel.explicitParameterName(parameter)));
            }
        }

        boolean hasExplicitName = parameters.stream()
                .anyMatch(parameter -> parameter.explicitName() != null);
        if (parameters.size() == 1 && !hasExplicitName) {
            return directContext(method, parameters.getFirst());
        }
        return namedContext(method, parameters);
    }

    private static @NotNull MyBatisParameterContext directContext(
            @NotNull PsiMethod method,
            @NotNull EffectiveParameter parameter) {
        List<MyBatisParameterBinding> bindings = new ArrayList<>();
        addBinding(
                bindings,
                "_parameter",
                parameter,
                MyBatisParameterBindingKind.PARAMETER_OBJECT,
                MyBatisParameterBindingCertainty.DEFINITE);
        boolean collection = isCollection(parameter.type());
        boolean list = isList(parameter.type());
        boolean array = parameter.type() instanceof PsiArrayType;
        if (collection) {
            addBinding(
                    bindings,
                    "collection",
                    parameter,
                    MyBatisParameterBindingKind.COLLECTION,
                    MyBatisParameterBindingCertainty.DEFINITE);
        }
        if (list) {
            addBinding(
                    bindings,
                    "list",
                    parameter,
                    MyBatisParameterBindingKind.LIST,
                    MyBatisParameterBindingCertainty.DEFINITE);
        }
        if (array) {
            addBinding(
                    bindings,
                    "array",
                    parameter,
                    MyBatisParameterBindingKind.ARRAY,
                    MyBatisParameterBindingCertainty.DEFINITE);
        }
        if (collection || array) {
            addBinding(
                    bindings,
                    parameter.parameter().getName(),
                    parameter,
                    MyBatisParameterBindingKind.ACTUAL_NAME,
                    MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
        }
        return new MyBatisParameterContext(
                method,
                MyBatisParameterRootMode.DIRECT,
                bindings,
                parameter.parameter(),
                parameter.type(),
                isMap(parameter.type()));
    }

    private static @NotNull MyBatisParameterContext namedContext(
            @NotNull PsiMethod method,
            @NotNull List<EffectiveParameter> parameters) {
        List<MyBatisParameterBinding> bindings = new ArrayList<>();
        Set<String> explicitNames = new HashSet<>();
        Set<String> actualNames = new HashSet<>();
        for (EffectiveParameter parameter : parameters) {
            ProgressManager.checkCanceled();
            if (parameter.explicitName() != null) {
                explicitNames.add(parameter.explicitName());
                addBinding(
                        bindings,
                        parameter.explicitName(),
                        parameter,
                        MyBatisParameterBindingKind.EXPLICIT,
                        MyBatisParameterBindingCertainty.DEFINITE);
            } else {
                actualNames.add(parameter.parameter().getName());
                addBinding(
                        bindings,
                        parameter.parameter().getName(),
                        parameter,
                        MyBatisParameterBindingKind.ACTUAL_NAME,
                        MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
                String reflectionName = "arg" + parameter.javaIndex();
                if (!reflectionName.equals(parameter.parameter().getName())) {
                    addBinding(
                            bindings,
                            reflectionName,
                            parameter,
                            MyBatisParameterBindingKind.REFLECTION_FALLBACK,
                            MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
                }
                addBinding(
                        bindings,
                        Integer.toString(parameter.effectiveIndex()),
                        parameter,
                        MyBatisParameterBindingKind.NUMERIC_FALLBACK,
                        MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT);
            }
        }
        for (EffectiveParameter parameter : parameters) {
            ProgressManager.checkCanceled();
            String genericName = "param" + (parameter.effectiveIndex() + 1);
            if (explicitNames.contains(genericName)) {
                continue;
            }
            addBinding(
                    bindings,
                    genericName,
                    parameter,
                    MyBatisParameterBindingKind.GENERIC_NAME,
                    actualNames.contains(genericName)
                            ? MyBatisParameterBindingCertainty.CONFIGURATION_DEPENDENT
                            : MyBatisParameterBindingCertainty.DEFINITE);
        }
        return new MyBatisParameterContext(
                method,
                MyBatisParameterRootMode.NAMED,
                bindings,
                null,
                null,
                false);
    }

    private static void addBinding(
            @NotNull List<MyBatisParameterBinding> bindings,
            @NotNull String name,
            @NotNull EffectiveParameter parameter,
            @NotNull MyBatisParameterBindingKind kind,
            @NotNull MyBatisParameterBindingCertainty certainty) {
        bindings.add(new MyBatisParameterBinding(
                name,
                parameter.parameter(),
                parameter.type(),
                parameter.javaIndex(),
                parameter.effectiveIndex(),
                kind,
                certainty));
    }

    private static @NotNull PsiType substituted(
            @NotNull PsiSubstitutor substitutor,
            @NotNull PsiType type) {
        PsiType substituted = substitutor.substitute(type);
        return substituted == null ? type : substituted;
    }

    private static boolean isSpecialParameter(@NotNull PsiType type) {
        return inherits(type, ROW_BOUNDS) || inherits(type, RESULT_HANDLER);
    }

    private static boolean isCollection(@NotNull PsiType type) {
        return isList(type)
                || inherits(type, "java.util.Collection")
                || inherits(type, "java.lang.Iterable");
    }

    private static boolean isList(@NotNull PsiType type) {
        return inherits(type, "java.util.List");
    }

    private static boolean isMap(@NotNull PsiType type) {
        return inherits(type, "java.util.Map");
    }

    private static boolean inherits(@NotNull PsiType type, @NotNull String qualifiedName) {
        PsiType erased = TypeConversionUtil.erasure(type);
        if (qualifiedName.equals(erased.getCanonicalText())) {
            return true;
        }
        PsiClass psiClass = erased instanceof PsiClassType classType
                ? classType.resolve()
                : null;
        return psiClass != null
                && (qualifiedName.equals(psiClass.getQualifiedName())
                || InheritanceUtil.isInheritor(psiClass, qualifiedName));
    }

    private record EffectiveParameter(
            @NotNull PsiParameter parameter,
            @NotNull PsiType type,
            int javaIndex,
            int effectiveIndex,
            @Nullable String explicitName) {
    }
}
