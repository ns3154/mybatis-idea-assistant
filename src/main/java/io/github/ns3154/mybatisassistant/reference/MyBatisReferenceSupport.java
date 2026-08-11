package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MyBatisReferenceSupport {
    private MyBatisReferenceSupport() {
    }

    static @Nullable XmlAttribute exactAttribute(
            @NotNull XmlAttributeValue value,
            @NotNull String name) {
        PsiElement parent = value.getParent();
        return parent instanceof XmlAttribute attribute && name.equals(attribute.getName())
                ? attribute
                : null;
    }

    static @NotNull TextRange valueRange(@NotNull XmlAttributeValue value) {
        return value.getValueTextRange().shiftLeft(value.getTextRange().getStartOffset());
    }

    static @Nullable XmlTag containingTag(@NotNull XmlAttribute attribute) {
        PsiElement parent = attribute.getParent();
        return parent instanceof XmlTag tag ? tag : null;
    }

    static @Nullable XmlTag mapperRoot(@NotNull XmlTag tag) {
        XmlTag current = tag;
        while (current.getParentTag() != null) {
            ProgressManager.checkCanceled();
            current = current.getParentTag();
        }
        return MyBatisXmlModel.isMapperRoot(current) ? current : null;
    }

    static @NotNull GlobalSearchScope resolveScope(@NotNull PsiElement context) {
        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        return module == null
                ? context.getResolveScope()
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
    }

    static @NotNull List<PsiClass> findMapperClasses(
            @NotNull PsiElement context,
            @NotNull String namespace) {
        Project project = context.getProject();
        PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(
                namespace,
                resolveScope(context));
        List<PsiClass> targets = new ArrayList<>();
        for (PsiClass candidate : classes) {
            ProgressManager.checkCanceled();
            if (candidate.isValid()
                    && candidate.isInterface()
                    && namespace.equals(candidate.getQualifiedName())) {
                targets.add(candidate);
            }
        }
        targets.sort(Comparator.comparing(MyBatisReferenceSupport::sourcePath)
                .thenComparingInt(PsiElement::getTextOffset));
        return List.copyOf(targets);
    }

    static boolean isStaticReferenceValue(@NotNull String value) {
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !normalized.contains("${")
                && !normalized.contains("#{")
                && normalized.indexOf(',') < 0
                && normalized.chars().noneMatch(Character::isWhitespace);
    }

    private static @NotNull String sourcePath(@NotNull PsiElement element) {
        ProgressManager.checkCanceled();
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return "";
        }
        VirtualFile virtualFile = file.getVirtualFile();
        return virtualFile == null ? file.getName() : virtualFile.getPath();
    }
}
