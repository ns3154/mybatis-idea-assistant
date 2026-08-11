package io.github.ns3154.mybatisassistant.model;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class MyBatisAnnotationModel {
    public static final String MAPPER_ANNOTATION = "org.apache.ibatis.annotations.Mapper";
    public static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";
    private static final String ANNOTATION_PACKAGE = "org.apache.ibatis.annotations.";
    private static final Set<String> INLINE_SQL_ANNOTATIONS = Set.of(
            "Select",
            "Insert",
            "Update",
            "Delete");
    private static final Set<String> PROVIDER_ANNOTATIONS = Set.of(
            "SelectProvider",
            "InsertProvider",
            "UpdateProvider",
            "DeleteProvider");

    private MyBatisAnnotationModel() {
    }

    public static boolean hasMapperAnnotation(@NotNull PsiClass psiClass) {
        ProgressManager.checkCanceled();
        for (PsiAnnotation annotation : psiClass.getAnnotations()) {
            ProgressManager.checkCanceled();
            if (MAPPER_ANNOTATION.equals(annotation.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    public static @NotNull MyBatisStatementSourceKind statementSource(@NotNull PsiMethod method) {
        for (PsiAnnotation annotation : method.getAnnotations()) {
            ProgressManager.checkCanceled();
            String shortName = myBatisAnnotationShortName(annotation);
            if (shortName == null) {
                continue;
            }
            if (INLINE_SQL_ANNOTATIONS.contains(shortName)) {
                return MyBatisStatementSourceKind.ANNOTATION_SQL;
            }
            if (PROVIDER_ANNOTATIONS.contains(shortName)) {
                return MyBatisStatementSourceKind.PROVIDER;
            }
            if ("Flush".equals(shortName)) {
                return MyBatisStatementSourceKind.FLUSH;
            }
        }
        return MyBatisStatementSourceKind.XML;
    }

    public static @Nullable String explicitParameterName(@NotNull PsiParameter parameter) {
        ProgressManager.checkCanceled();
        PsiAnnotation annotation = parameter.getAnnotation(PARAM_ANNOTATION);
        if (annotation == null) {
            return null;
        }
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value == null) {
            return null;
        }
        Object constant = JavaPsiFacade.getInstance(parameter.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(value);
        if (constant instanceof String name && !name.isBlank()) {
            return name.trim();
        }
        return null;
    }

    private static @Nullable String myBatisAnnotationShortName(@NotNull PsiAnnotation annotation) {
        String qualifiedName = annotation.getQualifiedName();
        if (qualifiedName == null || !qualifiedName.startsWith(ANNOTATION_PACKAGE)) {
            return null;
        }
        String shortName = qualifiedName.substring(ANNOTATION_PACKAGE.length());
        int dotSeparator = shortName.indexOf('.');
        int dollarSeparator = shortName.indexOf('$');
        int nestedTypeSeparator = dotSeparator < 0
                ? dollarSeparator
                : dollarSeparator < 0 ? dotSeparator : Math.min(dotSeparator, dollarSeparator);
        return nestedTypeSeparator < 0 ? shortName : shortName.substring(0, nestedTypeSeparator);
    }
}
