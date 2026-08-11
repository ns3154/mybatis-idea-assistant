package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import io.github.ns3154.mybatisassistant.resolve.MyBatisProviderMethodResolver;
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

        Project project = method.getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return;
        }

        try {
            collectNavigationMarkersWhenSmart(identifier, method, result);
        } catch (IndexNotReadyException ignored) {
            // Smart -> Dumb 竞态属于平台正常状态，行标应静默消失。
        }
    }

    private static void collectNavigationMarkersWhenSmart(
            @NotNull PsiIdentifier identifier,
            @NotNull PsiMethod method,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        PsiClass mapperInterface = method.getContainingClass();
        String namespace = mapperInterface == null ? null : mapperInterface.getQualifiedName();
        if (namespace == null) {
            return;
        }

        MyBatisStatementSourceKind sourceKind = MyBatisAnnotationModel.statementSource(method);
        if (sourceKind == MyBatisStatementSourceKind.ANNOTATION_SQL) {
            List<PsiAnnotation> annotations = MyBatisAnnotationModel.statementAnnotations(
                    method,
                    MyBatisStatementSourceKind.ANNOTATION_SQL);
            addMarker(
                    identifier,
                    result,
                    annotations,
                    "navigation.to.annotation.sql",
                    "navigation.annotation.target.name",
                    namespace,
                    method.getName());
            return;
        }
        if (sourceKind == MyBatisStatementSourceKind.PROVIDER) {
            addMarker(
                    identifier,
                    result,
                    MyBatisProviderMethodResolver.findNavigationTargets(method),
                    "navigation.to.provider.method",
                    "navigation.provider.target.name",
                    namespace,
                    method.getName());
            return;
        }
        if (sourceKind != MyBatisStatementSourceKind.XML) {
            return;
        }

        MyBatisStatementResolution resolution = MyBatisStatementResolver.resolve(method);
        List<XmlTag> targets = materializeTargets(resolution);
        addMarker(
                identifier,
                result,
                targets,
                "navigation.to.statement",
                "navigation.target.name",
                namespace,
                method.getName());
    }

    private static void addMarker(
            @NotNull PsiIdentifier identifier,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result,
            @NotNull List<? extends PsiElement> targets,
            @NotNull String tooltipKey,
            @NotNull String popupKey,
            @NotNull String namespace,
            @NotNull String methodName) {
        if (targets.isEmpty()) {
            return;
        }
        result.add(NavigationGutterIconBuilder
                .create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(targets)
                .setTooltipText(MyBatisAssistantBundle.message(tooltipKey))
                .setPopupTitle(MyBatisAssistantBundle.message(
                        popupKey,
                        namespace,
                        methodName))
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
