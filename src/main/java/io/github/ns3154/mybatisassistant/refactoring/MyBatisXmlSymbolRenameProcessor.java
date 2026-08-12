package io.github.ns3154.mybatisassistant.refactoring;

import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.MultiMap;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlSymbolKind;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MyBatisXmlSymbolRenameProcessor extends RenamePsiElementProcessor {
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return descriptor(element) != null;
    }

    @Override
    public void renameElement(
            @NotNull PsiElement element,
            @NotNull String newName,
            UsageInfo @NotNull [] usages,
            RefactoringElementListener listener) throws IncorrectOperationException {
        XmlTag tag = declarationTag(element);
        if (tag == null) {
            throw new IncorrectOperationException(MyBatisRefactoringMessages.message(
                    "refactoring.error.xml.symbol.invalid"));
        }
        for (UsageInfo usage : usages) {
            RenameUtil.rename(usage, newName);
        }
        tag.setAttribute("id", newName);
        if (listener != null) {
            listener.elementRenamed(tag);
        }
    }

    @Override
    public void findExistingNameConflicts(
            @NotNull PsiElement element,
            @NotNull String newName,
            @NotNull MultiMap<PsiElement, String> conflicts) {
        SymbolDescriptor descriptor = descriptor(element);
        if (descriptor == null) {
            return;
        }
        if (!element.isValid()
                || element.getProject().isDisposed()
                || !element.getProject().isOpen()) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.xml.symbol.invalid"));
            return;
        }
        if (newName.isBlank() || newName.chars().anyMatch(Character::isWhitespace)) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.xml.symbol.name"));
            return;
        }
        XmlTag mapper = descriptor.tag().getParentTag();
        if (mapper == null) {
            return;
        }
        if (DumbService.isDumb(element.getProject())) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.xml.symbol.indexing"));
            return;
        }
        String namespace = MyBatisXmlModel.namespace(mapper);
        String currentName = MyBatisXmlModel.symbolId(descriptor.tag());
        if (namespace == null || currentName == null) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.xml.symbol.incomplete"));
            return;
        }
        try {
            List<XmlTag> currentDeclarations = MyBatisXmlSymbolLocator.find(
                    element.getProject(),
                    descriptor.kind(),
                    namespace,
                    currentName,
                    element.getResolveScope());
            if (currentDeclarations.size() != 1) {
                conflicts.putValue(element, MyBatisRefactoringMessages.message(
                        "refactoring.conflict.xml.symbol.multiple"));
                return;
            }
            for (XmlTag conflict : MyBatisXmlSymbolLocator.find(
                    element.getProject(),
                    descriptor.kind(),
                    namespace,
                    newName,
                    element.getResolveScope())) {
                if (conflict != descriptor.tag()) {
                    conflicts.putValue(conflict, MyBatisRefactoringMessages.message(
                            "refactoring.conflict.xml.symbol.duplicate", newName));
                }
            }
        } catch (IndexNotReadyException ignored) {
            conflicts.putValue(element, MyBatisRefactoringMessages.message(
                    "refactoring.conflict.xml.symbol.index.changed"));
        }
    }

    private static @Nullable SymbolDescriptor descriptor(@NotNull PsiElement element) {
        XmlTag tag = declarationTag(element);
        if (tag == null || tag.getParentTag() == null || !MyBatisXmlModel.isMapperRoot(tag.getParentTag())) {
            return null;
        }
        MyBatisXmlSymbolKind kind = MyBatisXmlSymbolKind.fromMapperChildTag(tag.getName());
        return kind == MyBatisXmlSymbolKind.RESULT_MAP || kind == MyBatisXmlSymbolKind.SQL_FRAGMENT
                ? new SymbolDescriptor(tag, kind)
                : null;
    }

    private static @Nullable XmlTag declarationTag(@NotNull PsiElement element) {
        if (element instanceof XmlTag tag) {
            return tag;
        }
        if (element instanceof XmlAttribute attribute
                && "id".equals(attribute.getName())
                && attribute.getParent() instanceof XmlTag tag) {
            return tag;
        }
        if (element instanceof XmlAttributeValue value
                && value.getParent() instanceof XmlAttribute attribute) {
            return declarationTag(attribute);
        }
        return null;
    }

    private record SymbolDescriptor(
            @NotNull XmlTag tag,
            @NotNull MyBatisXmlSymbolKind kind) {
    }
}
