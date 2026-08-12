package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.ns3154.mybatisassistant.kotlin.MyBatisKotlinLightMethodResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

/**
 * 从不含运行期插值的 Kotlin 字符串中提取 MyBatis 注解 SQL。
 */
final class MyBatisKotlinAnnotationSqlSupport {
    private MyBatisKotlinAnnotationSqlSupport() {
    }

    static @Nullable KotlinAnnotationSqlLiteral inspect(
            @NotNull KtStringTemplateExpression literal) {
        ProgressManager.checkCanceled();
        if (literal.hasInterpolation()) {
            return null;
        }
        KtAnnotationEntry annotation = PsiTreeUtil.getParentOfType(
                literal,
                KtAnnotationEntry.class,
                true,
                KtNamedFunction.class);
        KtNamedFunction function = annotation == null
                ? null
                : PsiTreeUtil.getParentOfType(annotation, KtNamedFunction.class, true);
        PsiMethod method = function == null
                ? null
                : MyBatisKotlinLightMethodResolver.findSingle(function).orElse(null);
        if (annotation == null || method == null || !matchesStatementAnnotation(method, annotation)) {
            return null;
        }
        LiteralTextEscaper<?> escaper = literal.createLiteralTextEscaper();
        TextRange relevantRange = escaper.getRelevantTextRange();
        StringBuilder decoded = new StringBuilder();
        if (!escaper.decode(relevantRange, decoded)) {
            return null;
        }
        return new KotlinAnnotationSqlLiteral(
                method,
                decoded.toString(),
                escaper,
                relevantRange);
    }

    private static boolean matchesStatementAnnotation(
            @NotNull PsiMethod method,
            @NotNull KtAnnotationEntry source) {
        for (PsiAnnotation annotation : MyBatisAnnotationModel.statementAnnotations(
                method,
                MyBatisStatementSourceKind.ANNOTATION_SQL)) {
            ProgressManager.checkCanceled();
            PsiElement navigation = annotation.getNavigationElement();
            if (navigation.getManager().areElementsEquivalent(navigation, source)
                    || PsiTreeUtil.isAncestor(source, navigation, false)
                    || PsiTreeUtil.isAncestor(navigation, source, false)) {
                return true;
            }
        }
        return false;
    }

    record KotlinAnnotationSqlLiteral(
            @NotNull PsiMethod method,
            @NotNull String rawSql,
            @NotNull LiteralTextEscaper<?> escaper,
            @NotNull TextRange relevantRange) {
        @Nullable TextRange hostRange(@NotNull TextRange decodedRange) {
            int start = escaper.getOffsetInHost(
                    decodedRange.getStartOffset(),
                    relevantRange);
            int end = escaper.getOffsetInHost(
                    decodedRange.getEndOffset(),
                    relevantRange);
            return start < 0 || end < start ? null : new TextRange(start, end);
        }
    }
}
