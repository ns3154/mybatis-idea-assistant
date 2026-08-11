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
            throw new IncorrectOperationException("MyBatis XML 声明已失效");
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
            conflicts.putValue(element, "MyBatis XML 声明已失效，不能安全重命名");
            return;
        }
        if (newName.isBlank() || newName.chars().anyMatch(Character::isWhitespace)) {
            conflicts.putValue(element, "MyBatis XML 符号名称不能为空或包含空白");
            return;
        }
        XmlTag mapper = descriptor.tag().getParentTag();
        if (mapper == null) {
            return;
        }
        if (DumbService.isDumb(element.getProject())) {
            conflicts.putValue(element, "索引更新期间不能安全重命名 MyBatis XML 符号");
            return;
        }
        String namespace = MyBatisXmlModel.namespace(mapper);
        String currentName = MyBatisXmlModel.symbolId(descriptor.tag());
        if (namespace == null || currentName == null) {
            conflicts.putValue(element, "MyBatis XML 声明不完整，不能安全重命名");
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
                conflicts.putValue(element, "当前 MyBatis XML 符号存在多个声明，已停止重命名");
                return;
            }
            for (XmlTag conflict : MyBatisXmlSymbolLocator.find(
                    element.getProject(),
                    descriptor.kind(),
                    namespace,
                    newName,
                    element.getResolveScope())) {
                if (conflict != descriptor.tag()) {
                    conflicts.putValue(conflict, "同一 namespace 已存在 " + newName);
                }
            }
        } catch (IndexNotReadyException ignored) {
            conflicts.putValue(element, "索引状态已变化，不能安全重命名 MyBatis XML 符号");
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
