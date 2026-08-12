package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.HierarchicalMethodSignature;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从锁定的框架基类解析 Mapper 实体和框架内建方法。
 */
public final class MyBatisFrameworkMapperResolver {
    private MyBatisFrameworkMapperResolver() {
    }

    public static @NotNull MyBatisFrameworkMapperResolution resolve(
            @NotNull PsiClass mapper) {
        ProgressManager.checkCanceled();
        Project project = mapper.getProject();
        if (!mapper.isValid() || project.isDisposed() || !project.isOpen()) {
            return new MyBatisFrameworkMapperResolution.SourceInvalid();
        }
        if (!mapper.isInterface() || mapper.isAnnotationType()) {
            return new MyBatisFrameworkMapperResolution.Unsupported("源必须是 Mapper 接口");
        }
        if (DumbService.isDumb(project)) {
            return new MyBatisFrameworkMapperResolution.IndexNotReady();
        }
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (MyBatisFrameworkKind framework : MyBatisFrameworkKind.values()) {
                ProgressManager.checkCanceled();
                PsiClass[] baseMappers = JavaPsiFacade.getInstance(project).findClasses(
                        framework.baseMapperQualifiedName(),
                        mapper.getResolveScope());
                for (PsiClass baseMapper : baseMappers) {
                    ProgressManager.checkCanceled();
                    if (!mapper.isEquivalentTo(baseMapper)
                            && mapper.isInheritor(baseMapper, true)) {
                        candidates.add(new Candidate(framework, baseMapper));
                    }
                }
            }
            if (candidates.isEmpty()) {
                return new MyBatisFrameworkMapperResolution.NotFrameworkMapper();
            }
            if (candidates.size() != 1) {
                return new MyBatisFrameworkMapperResolution.Unsupported(
                        "Mapper 同时继承多个已支持框架基类");
            }
            return binding(mapper, candidates.getFirst());
        } catch (IndexNotReadyException ignored) {
            return new MyBatisFrameworkMapperResolution.IndexNotReady();
        }
    }

    private static @NotNull MyBatisFrameworkMapperResolution binding(
            @NotNull PsiClass mapper,
            @NotNull Candidate candidate) {
        PsiClass baseMapper = candidate.baseMapper();
        PsiTypeParameter[] parameters = baseMapper.getTypeParameters();
        if (parameters.length != 1) {
            return new MyBatisFrameworkMapperResolution.Unsupported(
                    candidate.framework().displayName() + " 基类泛型形状不受支持");
        }
        PsiSubstitutor substitutor = TypeConversionUtil.getSuperClassSubstitutor(
                baseMapper,
                mapper,
                PsiSubstitutor.EMPTY);
        PsiType entityType = substitutor.substitute(parameters[0]);
        PsiClass entityClass = entityType instanceof PsiClassType classType
                ? classType.resolve()
                : null;
        if (entityClass == null || entityClass instanceof PsiTypeParameter) {
            return new MyBatisFrameworkMapperResolution.Unsupported(
                    candidate.framework().displayName() + " 实体泛型必须是可解析的具体类");
        }
        MyBatisEntityModel entity = MyBatisEntityModelFactory.create(entityType, mapper);
        if (entity.kind() != MyBatisEntityKind.CLASS || entity.qualifiedName() == null) {
            return new MyBatisFrameworkMapperResolution.Unsupported(
                    candidate.framework().displayName() + " 实体泛型必须是具体实体类");
        }
        return new MyBatisFrameworkMapperResolution.Found(
                new MyBatisFrameworkMapperBinding(
                        candidate.framework(),
                        entity,
                        frameworkMethodSignatures(mapper, baseMapper),
                        frameworkDeclaringTypes(baseMapper)));
    }

    private static @NotNull List<String> frameworkMethodSignatures(
            @NotNull PsiClass mapper,
            @NotNull PsiClass baseMapper) {
        Set<String> signatures = new LinkedHashSet<>();
        for (HierarchicalMethodSignature signature : mapper.getVisibleSignatures()) {
            ProgressManager.checkCanceled();
            PsiMethod method = signature.getMethod();
            PsiClass declaringClass = method.getContainingClass();
            if (declaringClass == null
                    || !(baseMapper.isEquivalentTo(declaringClass)
                    || baseMapper.isInheritor(declaringClass, true))) {
                continue;
            }
            signatures.add(stableSignature(method, signature.getSubstitutor()));
        }
        return signatures.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static @NotNull String stableSignature(
            @NotNull PsiMethod method,
            @NotNull PsiSubstitutor substitutor) {
        StringBuilder result = new StringBuilder(method.getName()).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int index = 0; index < parameters.length; index++) {
            ProgressManager.checkCanceled();
            if (index > 0) {
                result.append(',');
            }
            PsiType type = parameters[index].getType();
            PsiType substituted = substitutor.substitute(type);
            result.append((substituted == null ? type : substituted).getCanonicalText());
        }
        return result.append(')').toString();
    }

    private static @NotNull List<String> frameworkDeclaringTypes(
            @NotNull PsiClass baseMapper) {
        Set<String> result = new LinkedHashSet<>();
        collectDeclaringTypes(baseMapper, new LinkedHashSet<>(), result);
        return result.stream().sorted().toList();
    }

    private static void collectDeclaringTypes(
            @NotNull PsiClass type,
            @NotNull Set<PsiClass> visited,
            @NotNull Set<String> result) {
        ProgressManager.checkCanceled();
        if (!visited.add(type)) {
            return;
        }
        String qualifiedName = type.getQualifiedName();
        if (qualifiedName != null) {
            result.add(qualifiedName);
        }
        for (PsiClass superType : type.getSupers()) {
            collectDeclaringTypes(superType, visited, result);
        }
    }

    private record Candidate(
            @NotNull MyBatisFrameworkKind framework,
            @NotNull PsiClass baseMapper) {
    }
}
