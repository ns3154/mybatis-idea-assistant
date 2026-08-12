package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * 为 Kotlin MyBatis 直接 SQL 注解提供参数引用。
 */
public final class MyBatisKotlinReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(KtStringTemplateExpression.class),
                new AnnotationParameterReferenceProvider());
    }

    private static final class AnnotationParameterReferenceProvider extends PsiReferenceProvider {
        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            if (!(element instanceof KtStringTemplateExpression literal)
                    || element.getProject().isDisposed()
                    || !element.getProject().isOpen()
                    || DumbService.isDumb(element.getProject())) {
                return PsiReference.EMPTY_ARRAY;
            }
            try {
                MyBatisKotlinAnnotationSqlSupport.KotlinAnnotationSqlLiteral sql =
                        MyBatisKotlinAnnotationSqlSupport.inspect(literal);
                if (sql == null) {
                    return PsiReference.EMPTY_ARRAY;
                }
                List<PsiReference> references = new ArrayList<>();
                for (MyBatisParameterExpressionParser.ParameterPath path
                        : MyBatisParameterExpressionParser.parsePlaceholders(sql.rawSql())) {
                    for (int index = 0; index < path.segments().size(); index++) {
                        ProgressManager.checkCanceled();
                        TextRange range = sql.hostRange(path.segments().get(index).range());
                        if (range != null && !range.isEmpty()) {
                            references.add(new MyBatisAnnotationParameterReference(
                                    literal,
                                    range,
                                    sql.method(),
                                    path,
                                    index));
                        }
                    }
                }
                return references.toArray(PsiReference.EMPTY_ARRAY);
            } catch (IndexNotReadyException ignored) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
    }
}
