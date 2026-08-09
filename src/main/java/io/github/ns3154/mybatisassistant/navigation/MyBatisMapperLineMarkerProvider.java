package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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

        MyBatisStatementResolution resolution = MyBatisStatementResolver.resolve(method);
        List<XmlTag> targets = materializeTargets(resolution);
        if (targets.isEmpty()) {
            return;
        }
        PsiClass mapperInterface = method.getContainingClass();
        if (mapperInterface == null) {
            return;
        }
        String namespace = mapperInterface.getQualifiedName();
        if (namespace == null) {
            return;
        }

        result.add(NavigationGutterIconBuilder
                .create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(targets)
                .setTooltipText(MyBatisAssistantBundle.message("navigation.to.statement"))
                .setPopupTitle(MyBatisAssistantBundle.message(
                        "navigation.target.name",
                        namespace,
                        method.getName()))
                .createLineMarkerInfo(identifier));
    }

    static @NotNull List<XmlTag> findTargets(@NotNull PsiMethod method) {
        return materializeTargets(MyBatisStatementResolver.resolve(method));
    }

    private static @NotNull List<XmlTag> materializeTargets(
            @NotNull MyBatisStatementResolution resolution) {
        List<SmartPsiElementPointer<XmlTag>> pointers = switch (resolution) {
            case MyBatisStatementResolution.UniqueMatch uniqueMatch ->
                    List.of(uniqueMatch.target());
            case MyBatisStatementResolution.MultipleMatches multipleMatches ->
                    multipleMatches.targets();
            default -> List.of();
        };

        List<XmlTag> targets = new ArrayList<>(pointers.size());
        for (SmartPsiElementPointer<XmlTag> pointer : pointers) {
            ProgressManager.checkCanceled();
            XmlTag target = pointer.getElement();
            if (target != null && target.isValid()) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }
}
