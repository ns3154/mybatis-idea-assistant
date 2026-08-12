package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.kotlin.MyBatisKotlinLightMethodResolver;
import io.github.ns3154.mybatisassistant.model.MyBatisAnnotationModel;
import io.github.ns3154.mybatisassistant.model.MyBatisStatementSourceKind;
import io.github.ns3154.mybatisassistant.resolve.MyBatisProviderMethodResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Kotlin K2 Mapper 函数导航到 XML、直接 SQL 注解或 Provider 方法。
 */
public final class MyBatisKotlinMapperLineMarkerProvider
        implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element.getParent() instanceof KtNamedFunction function)
                || function.getNameIdentifier() != element) {
            return null;
        }
        Project project = function.getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return null;
        }
        try {
            Navigation navigation = navigation(function);
            return navigation == null ? null : createMarker(element, navigation);
        } catch (IndexNotReadyException ignored) {
            // Smart -> Dumb 竞态时保持静默。
            return null;
        }
    }

    public static @NotNull List<PsiElement> findTargets(@NotNull KtNamedFunction function) {
        Project project = function.getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return List.of();
        }
        try {
            Navigation navigation = navigation(function);
            return navigation == null ? List.of() : navigation.targets();
        } catch (IndexNotReadyException ignored) {
            return List.of();
        }
    }

    private static @Nullable Navigation navigation(@NotNull KtNamedFunction function) {
        ProgressManager.checkCanceled();
        PsiMethod method = MyBatisKotlinLightMethodResolver.findSingle(function).orElse(null);
        if (method == null) {
            return null;
        }
        PsiClass mapper = method.getContainingClass();
        String namespace = mapper == null ? null : mapper.getQualifiedName();
        if (namespace == null) {
            return null;
        }
        MyBatisStatementSourceKind source = MyBatisAnnotationModel.statementSource(method);
        List<? extends PsiElement> targets;
        String tooltipKey;
        String popupKey;
        switch (source) {
            case XML -> {
                targets = MyBatisMapperLineMarkerProvider.findTargets(method);
                tooltipKey = "navigation.to.statement";
                popupKey = "navigation.target.name";
            }
            case ANNOTATION_SQL -> {
                targets = annotationTargets(method);
                tooltipKey = "navigation.to.annotation.sql";
                popupKey = "navigation.annotation.target.name";
            }
            case PROVIDER -> {
                targets = MyBatisProviderMethodResolver.findNavigationTargets(method);
                tooltipKey = "navigation.to.provider.method";
                popupKey = "navigation.provider.target.name";
            }
            default -> {
                return null;
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        return new Navigation(
                new ArrayList<>(targets),
                tooltipKey,
                popupKey,
                namespace,
                method.getName());
    }

    private static @NotNull List<PsiElement> annotationTargets(@NotNull PsiMethod method) {
        return MyBatisAnnotationModel.statementAnnotations(
                        method,
                        MyBatisStatementSourceKind.ANNOTATION_SQL)
                .stream()
                .peek(annotation -> ProgressManager.checkCanceled())
                .map(PsiAnnotation::getNavigationElement)
                .filter(PsiElement::isValid)
                .toList();
    }

    private static @NotNull LineMarkerInfo<PsiElement> createMarker(
            @NotNull PsiElement anchor,
            @NotNull Navigation navigation) {
        String tooltip = MyBatisAssistantBundle.message(navigation.tooltipKey());
        var related = NavigationGutterIconBuilder
                .create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(navigation.targets())
                .setTooltipText(tooltip)
                .setPopupTitle(MyBatisAssistantBundle.message(
                        navigation.popupKey(),
                        navigation.namespace(),
                        navigation.methodName()))
                .createLineMarkerInfo(anchor);
        return new LineMarkerInfo<>(
                anchor,
                anchor.getTextRange(),
                related.getIcon(),
                ignored -> tooltip,
                related.getNavigationHandler(),
                GutterIconRenderer.Alignment.CENTER,
                () -> tooltip);
    }

    private record Navigation(
            @NotNull List<PsiElement> targets,
            @NotNull String tooltipKey,
            @NotNull String popupKey,
            @NotNull String namespace,
            @NotNull String methodName) {
        private Navigation {
            targets = List.copyOf(targets);
        }
    }
}
