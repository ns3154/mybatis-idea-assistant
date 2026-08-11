package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class MyBatisEntityModelFactory {
    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of(
            "org.jetbrains.annotations.Nullable",
            "org.jspecify.annotations.Nullable",
            "javax.annotation.Nullable",
            "jakarta.annotation.Nullable");
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.lang.Iterable",
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
            "java.util.SortedSet",
            "java.util.NavigableSet",
            "java.util.Queue",
            "java.util.Deque");

    private MyBatisEntityModelFactory() {
    }

    static @NotNull MyBatisEntityModel create(
            @NotNull PsiType type,
            @Nullable PsiModifierListOwner owner) {
        ProgressManager.checkCanceled();
        boolean nullable = owner != null
                && NULLABLE_ANNOTATIONS.stream().anyMatch(owner::hasAnnotation);
        return create(type, nullable);
    }

    private static @NotNull MyBatisEntityModel create(
            @NotNull PsiType type,
            boolean nullable) {
        ProgressManager.checkCanceled();
        if (PsiTypes.voidType().equals(type)) {
            return model(type, MyBatisEntityKind.VOID, null, List.of(), false);
        }
        if (type instanceof PsiPrimitiveType) {
            return model(type, MyBatisEntityKind.PRIMITIVE, null, List.of(), false);
        }
        if (type instanceof PsiArrayType arrayType) {
            return model(
                    type,
                    MyBatisEntityKind.ARRAY,
                    null,
                    List.of(create(arrayType.getComponentType(), false)),
                    nullable);
        }
        if (type instanceof PsiWildcardType wildcardType) {
            PsiType bound = wildcardType.getBound();
            List<MyBatisEntityModel> arguments = bound == null
                    ? List.of()
                    : List.of(create(bound, false));
            return model(type, MyBatisEntityKind.WILDCARD, null, arguments, nullable);
        }
        if (type instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            String qualifiedName = resolved == null ? null : resolved.getQualifiedName();
            if (qualifiedName == null) {
                String rawType = classType.rawType().getCanonicalText();
                qualifiedName = rawType.indexOf('.') > 0 ? rawType : null;
            }
            List<MyBatisEntityModel> arguments = Arrays.stream(classType.getParameters())
                    .map(argument -> create(argument, false))
                    .toList();
            return model(
                    type,
                    resolved instanceof PsiTypeParameter
                            ? MyBatisEntityKind.TYPE_PARAMETER
                            : classKind(resolved, qualifiedName),
                    qualifiedName,
                    arguments,
                    nullable);
        }
        return model(type, MyBatisEntityKind.CLASS, null, List.of(), nullable);
    }

    private static @NotNull MyBatisEntityKind classKind(
            @Nullable PsiClass resolved,
            @Nullable String qualifiedName) {
        if ("java.util.Map".equals(qualifiedName)
                || resolved != null && InheritanceUtil.isInheritor(resolved, "java.util.Map")) {
            return MyBatisEntityKind.MAP;
        }
        if (qualifiedName != null && COLLECTION_TYPES.contains(qualifiedName)
                || resolved != null && (InheritanceUtil.isInheritor(resolved, "java.util.Collection")
                || InheritanceUtil.isInheritor(resolved, "java.lang.Iterable"))) {
            return MyBatisEntityKind.COLLECTION;
        }
        if ("java.util.Optional".equals(qualifiedName)) {
            return MyBatisEntityKind.OPTIONAL;
        }
        if ("java.util.stream.Stream".equals(qualifiedName)) {
            return MyBatisEntityKind.STREAM;
        }
        return MyBatisEntityKind.CLASS;
    }

    private static @NotNull MyBatisEntityModel model(
            @NotNull PsiType type,
            @NotNull MyBatisEntityKind kind,
            @Nullable String qualifiedName,
            @NotNull List<MyBatisEntityModel> arguments,
            boolean nullable) {
        return new MyBatisEntityModel(
                type.getCanonicalText(),
                kind,
                qualifiedName,
                arguments,
                nullable);
    }
}
