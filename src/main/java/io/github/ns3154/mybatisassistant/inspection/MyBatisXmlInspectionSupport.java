package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class MyBatisXmlInspectionSupport {
    private MyBatisXmlInspectionSupport() {
    }

    static boolean isReady(@NotNull PsiElement element) {
        Project project = element.getProject();
        return element.isValid()
                && !project.isDisposed()
                && project.isOpen()
                && !DumbService.isDumb(project);
    }

    static boolean isDirectStatement(@NotNull XmlTag tag) {
        XmlTag mapper = tag.getParentTag();
        return mapper != null
                && MyBatisXmlModel.isMapperRoot(mapper)
                && MyBatisXmlModel.isStatement(tag);
    }

    static @Nullable XmlAttributeValue exactAttributeValue(
            @NotNull XmlTag tag,
            @NotNull String attributeName) {
        XmlAttribute attribute = tag.getAttribute(attributeName);
        return attribute == null || !attributeName.equals(attribute.getName())
                ? null
                : attribute.getValueElement();
    }

    static boolean isStaticValue(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.isEmpty()
                && !normalized.contains("${")
                && !normalized.contains("#{")
                && normalized.chars().noneMatch(Character::isWhitespace);
    }

    static @NotNull GlobalSearchScope resolveScope(@NotNull PsiElement context) {
        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        return module == null
                ? context.getResolveScope()
                : GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);
    }

    static boolean hasExactMapperInterface(
            @NotNull PsiElement context,
            @NotNull String namespace) {
        return !findExactMapperInterfaces(context, namespace).isEmpty();
    }

    static @NotNull List<PsiClass> findExactMapperInterfaces(
            @NotNull PsiElement context,
            @NotNull String namespace) {
        ProgressManager.checkCanceled();
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass candidate : JavaPsiFacade.getInstance(context.getProject()).findClasses(
                namespace,
                resolveScope(context))) {
            ProgressManager.checkCanceled();
            if (candidate.isValid()
                    && candidate.isInterface()
                    && namespace.equals(candidate.getQualifiedName())) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }
}
