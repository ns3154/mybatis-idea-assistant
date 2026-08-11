package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import org.jetbrains.annotations.NotNull;

public final class MyBatisJavaReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(PsiLiteralExpression.class),
                new ProviderMethodReferenceProvider());
    }

    private static final class ProviderMethodReferenceProvider extends PsiReferenceProvider {
        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            Project project = element.getProject();
            if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
                return PsiReference.EMPTY_ARRAY;
            }
            try {
                if (!(element instanceof PsiLiteralExpression literal)
                        || !(literal.getValue() instanceof String methodName)
                        || !(literal.getParent() instanceof PsiNameValuePair pair)
                        || !"method".equals(pair.getName())
                        || !(PsiTreeUtil.getParentOfType(pair, PsiAnnotation.class)
                                instanceof PsiAnnotation annotation)
                        || !MyBatisAnnotationModel.isProviderAnnotation(annotation)
                        || !PsiNameHelper.getInstance(project).isIdentifier(methodName)) {
                    return PsiReference.EMPTY_ARRAY;
                }
                return new PsiReference[]{new MyBatisProviderMethodReference(literal, annotation)};
            } catch (IndexNotReadyException ignored) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
    }
}
