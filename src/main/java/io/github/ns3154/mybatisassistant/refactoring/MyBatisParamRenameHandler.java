package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 让原生 Rename 动作能够从 {@code @Param("...")} 字符串光标位置进入安全重构链路。
 */
public final class MyBatisParamRenameHandler implements RenameHandler {
    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        return paramLiteral(dataContext) != null;
    }

    @Override
    public boolean isRenaming(@NotNull DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    public void invoke(
            @NotNull Project project,
            Editor editor,
            PsiFile file,
            DataContext dataContext) {
        PsiLiteralExpression literal = paramLiteral(dataContext);
        if (literal != null) {
            PsiElementRenameHandler.rename(literal, project, literal, editor);
        }
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiElement[] elements,
            DataContext dataContext) {
        if (elements.length == 1
                && elements[0] instanceof PsiLiteralExpression literal
                && MyBatisParamRenameProcessor.isParamLiteral(literal)) {
            PsiElementRenameHandler.rename(literal, project, literal, null);
        }
    }

    private static @Nullable PsiLiteralExpression paramLiteral(
            @NotNull DataContext dataContext) {
        PsiElement direct = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
        PsiLiteralExpression directLiteral = containingParamLiteral(direct);
        if (directLiteral != null) {
            return directLiteral;
        }
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (editor == null || file == null || file.getTextLength() == 0) {
            return null;
        }
        int offset = Math.min(editor.getCaretModel().getOffset(), file.getTextLength() - 1);
        PsiLiteralExpression literal = containingParamLiteral(file.findElementAt(offset));
        return literal != null || offset == 0
                ? literal
                : containingParamLiteral(file.findElementAt(offset - 1));
    }

    private static @Nullable PsiLiteralExpression containingParamLiteral(
            @Nullable PsiElement element) {
        PsiLiteralExpression literal = element instanceof PsiLiteralExpression direct
                ? direct
                : PsiTreeUtil.getParentOfType(element, PsiLiteralExpression.class, false);
        return literal != null && MyBatisParamRenameProcessor.isParamLiteral(literal)
                ? literal
                : null;
    }
}
