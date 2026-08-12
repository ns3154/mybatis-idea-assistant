package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.MultiMap;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 通过平台 Rename 同步修改 bind、foreach 声明及其 OGNL 引用。
 */
public final class MyBatisOgnlBindingRenameProcessor extends RenamePsiElementProcessor {
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return declaration(element) != null;
    }

    @Override
    public void renameElement(
            @NotNull PsiElement element,
            @NotNull String newName,
            UsageInfo @NotNull [] usages,
            RefactoringElementListener listener) throws IncorrectOperationException {
        Declaration declaration = declaration(element);
        if (declaration == null || !element.isValid()) {
            throw new IncorrectOperationException(MyBatisRefactoringMessages.message(
                    "refactoring.error.ognl.binding.invalid"));
        }
        for (UsageInfo usage : usages) {
            ProgressManager.checkCanceled();
            RenameUtil.rename(usage, newName);
        }
        declaration.attribute().setValue(newName);
        XmlAttributeValue renamed = declaration.attribute().getValueElement();
        if (listener != null && renamed != null) {
            listener.elementRenamed(renamed);
        }
    }

    @Override
    public void findExistingNameConflicts(
            @NotNull PsiElement element,
            @NotNull String newName,
            @NotNull MultiMap<PsiElement, String> conflicts) {
        Declaration declaration = declaration(element);
        if (declaration == null) {
            return;
        }
        if (!element.isValid()
                || element.getProject().isDisposed()
                || !element.getProject().isOpen()) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.ognl.binding.invalid"));
            return;
        }
        if (newName.isBlank()
                || !PsiNameHelper.getInstance(element.getProject()).isIdentifier(newName)) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.ognl.binding.name"));
            return;
        }
        if (newName.equals(declaration.value().getValue())) {
            return;
        }
        if (DumbService.isDumb(element.getProject())) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.ognl.binding.indexing"));
            return;
        }
        XmlTag statement = containingStatement(declaration.tag());
        if (statement == null) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.ognl.binding.statement"));
            return;
        }
        for (XmlAttributeValue candidate : PsiTreeUtil.findChildrenOfType(
                statement,
                XmlAttributeValue.class)) {
            ProgressManager.checkCanceled();
            if (candidate != declaration.value()
                    && newName.equals(candidate.getValue())
                    && declaration(candidate) != null) {
                conflicts.putValue(candidate, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.ognl.binding.duplicate", newName));
            }
        }
    }

    private static @Nullable Declaration declaration(@NotNull PsiElement element) {
        if (!(element instanceof XmlAttributeValue value)
                || !(value.getParent() instanceof XmlAttribute attribute)
                || !(attribute.getParent() instanceof XmlTag tag)) {
            return null;
        }
        String tagName = tag.getName();
        String attributeName = attribute.getName();
        boolean supported = "bind".equals(tagName) && "name".equals(attributeName)
                || "foreach".equals(tagName)
                && ("item".equals(attributeName) || "index".equals(attributeName));
        return supported ? new Declaration(value, attribute, tag) : null;
    }

    private static @Nullable XmlTag containingStatement(@NotNull XmlTag tag) {
        XmlTag current = tag;
        while (current != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(current)) {
                return current;
            }
            current = current.getParentTag();
        }
        return null;
    }

    private record Declaration(
            @NotNull XmlAttributeValue value,
            @NotNull XmlAttribute attribute,
            @NotNull XmlTag tag) {
    }
}
