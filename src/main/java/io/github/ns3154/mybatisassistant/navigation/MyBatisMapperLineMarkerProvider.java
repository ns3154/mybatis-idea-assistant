package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.index.MyBatisStatementLocator;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public final class MyBatisMapperLineMarkerProvider extends RelatedItemLineMarkerProvider {
    @Override
    public void collectNavigationMarkers(
            @NotNull PsiElement element,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        if (!(element instanceof PsiIdentifier identifier)
                || !(identifier.getParent() instanceof PsiMethod method)) {
            return;
        }

        List<XmlTag> targets = findTargets(method);
        if (targets.isEmpty()) {
            return;
        }

        result.add(NavigationGutterIconBuilder
                .create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(targets)
                .setTooltipText(MyBatisAssistantBundle.message("navigation.to.statement"))
                .setPopupTitle(MyBatisAssistantBundle.message(
                        "navigation.target.name",
                        method.getContainingClass().getQualifiedName(),
                        method.getName()))
                .createLineMarkerInfo(identifier));
    }

    static @NotNull List<XmlTag> findTargets(@NotNull PsiMethod method) {
        if (!method.isValid()) {
            return List.of();
        }
        PsiClass mapperInterface = method.getContainingClass();
        if (mapperInterface == null || !mapperInterface.isInterface()) {
            return List.of();
        }

        String qualifiedName = mapperInterface.getQualifiedName();
        if (qualifiedName == null
                || mapperInterface.findMethodsByName(method.getName(), false).length != 1) {
            return List.of();
        }

        return MyBatisStatementLocator.find(method.getProject(), qualifiedName, method.getName());
    }
}
