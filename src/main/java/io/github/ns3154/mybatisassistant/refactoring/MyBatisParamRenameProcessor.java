package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.MultiMap;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import org.jetbrains.annotations.NotNull;

public final class MyBatisParamRenameProcessor extends RenamePsiElementProcessor {
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof PsiLiteralExpression literal && isParamLiteral(literal);
    }

    @Override
    public void renameElement(
            @NotNull PsiElement element,
            @NotNull String newName,
            UsageInfo @NotNull [] usages,
            RefactoringElementListener listener) throws IncorrectOperationException {
        PsiLiteralExpression literal = (PsiLiteralExpression) element;
        for (UsageInfo usage : usages) {
            RenameUtil.rename(usage, newName);
        }
        String text = '"' + StringUtil.escapeStringCharacters(newName) + '"';
        PsiElement replacement = JavaPsiFacade.getElementFactory(element.getProject())
                .createExpressionFromText(text, element);
        PsiElement renamed = literal.replace(replacement);
        if (listener != null) {
            listener.elementRenamed(renamed);
        }
    }

    @Override
    public void findExistingNameConflicts(
            @NotNull PsiElement element,
            @NotNull String newName,
            @NotNull MultiMap<PsiElement, String> conflicts) {
        PsiParameter current = PsiTreeUtil.getParentOfType(element, PsiParameter.class);
        PsiMethod method = current == null ? null : PsiTreeUtil.getParentOfType(current, PsiMethod.class);
        if (newName.isBlank()) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.empty"));
            return;
        }
        if (!PsiNameHelper.getInstance(element.getProject()).isIdentifier(newName)) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.identifier"));
            return;
        }
        if (method == null) {
            return;
        }
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (parameter != current
                    && newName.equals(MyBatisAnnotationModel.explicitParameterName(parameter))) {
                conflicts.putValue(parameter, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.param.duplicate", newName));
            }
        }
        if (!conflicts.isEmpty()
                || element instanceof PsiLiteralExpression literal
                && newName.equals(literal.getValue())) {
            return;
        }
        if (DumbService.isDumb(element.getProject())) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.indexing"));
            return;
        }
        try {
            MyBatisParamRenameSafety.Conflict conflict = MyBatisParamRenameSafety.findConflict(
                    (PsiLiteralExpression) element,
                    method);
            if (conflict != null) {
                conflicts.putValue(conflict.element(), conflict.message());
            }
        } catch (IndexNotReadyException ignored) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.param.index.changed"));
        }
    }

    public static boolean isParamLiteral(@NotNull PsiLiteralExpression literal) {
        if (!(literal.getValue() instanceof String)
                || !(PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class)
                instanceof PsiAnnotation annotation)
                || !MyBatisAnnotationModel.PARAM_ANNOTATION.equals(annotation.getQualifiedName())) {
            return false;
        }
        PsiParameter parameter = PsiTreeUtil.getParentOfType(annotation, PsiParameter.class);
        return parameter != null
                && MyBatisAnnotationModel.explicitParameterNameElement(parameter) == literal;
    }
}
