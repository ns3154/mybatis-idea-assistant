package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MyBatisAnnotationSqlSupport {
    private MyBatisAnnotationSqlSupport() {
    }

    public static @Nullable AnnotationSqlLiteral inspect(@NotNull PsiLiteralExpression literal) {
        ProgressManager.checkCanceled();
        if (!(literal.getValue() instanceof String)) {
            return null;
        }
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(
                literal,
                PsiAnnotation.class,
                true,
                PsiMethod.class);
        PsiMethod method = annotation == null
                ? null
                : PsiTreeUtil.getParentOfType(annotation, PsiMethod.class, true);
        if (annotation == null || method == null) {
            return null;
        }
        boolean supported = MyBatisAnnotationModel.statementAnnotations(
                        method,
                        MyBatisStatementSourceKind.ANNOTATION_SQL)
                .stream()
                .anyMatch(candidate -> candidate.getManager()
                        .areElementsEquivalent(candidate, annotation));
        if (!supported) {
            return null;
        }
        TextRange valueRange = ElementManipulators.getValueTextRange(literal);
        if (valueRange.isEmpty() || valueRange.getEndOffset() > literal.getTextLength()) {
            return null;
        }
        return new AnnotationSqlLiteral(
                method,
                literal.getText().substring(
                        valueRange.getStartOffset(),
                        valueRange.getEndOffset()),
                valueRange);
    }

    public static @NotNull java.util.List<String> completionVariants(
            @NotNull PsiLiteralExpression literal,
            int offsetInLiteral) {
        ProgressManager.checkCanceled();
        AnnotationSqlLiteral sql = inspect(literal);
        if (sql == null || !sql.valueRange().containsOffset(offsetInLiteral)) {
            return java.util.List.of();
        }
        int rawOffset = Math.max(0, Math.min(
                offsetInLiteral - sql.valueRange().getStartOffset(),
                sql.rawSql().length()));
        String prefix = sql.rawSql().substring(0, rawOffset);
        int hash = prefix.lastIndexOf("#{");
        int dollar = prefix.lastIndexOf("${");
        int placeholderStart = Math.max(hash, dollar);
        int closingBrace = prefix.lastIndexOf('}');
        if (placeholderStart < 0 || closingBrace > placeholderStart) {
            return java.util.List.of();
        }
        String probe = prefix.substring(placeholderStart) + "__completion__}";
        java.util.List<MyBatisParameterExpressionParser.ParameterPath> paths =
                MyBatisParameterExpressionParser.parsePlaceholders(probe);
        if (paths.isEmpty()) {
            return java.util.List.of();
        }
        MyBatisParameterExpressionParser.ParameterPath path = paths.getLast();
        if (path.segments().isEmpty()) {
            return java.util.List.of();
        }
        return MyBatisParameterPathResolver.resolveAnnotation(
                        literal,
                        sql.method(),
                        path,
                        path.segments().size() - 1)
                .variants();
    }

    public record AnnotationSqlLiteral(
            @NotNull PsiMethod method,
            @NotNull String rawSql,
            @NotNull TextRange valueRange) {
    }
}
