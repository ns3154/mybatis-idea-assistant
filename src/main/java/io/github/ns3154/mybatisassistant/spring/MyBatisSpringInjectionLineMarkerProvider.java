package io.github.ns3154.mybatisassistant.spring;

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
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.index.MyBatisXmlSymbolLocator;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolution;
import io.github.ns3154.mybatisassistant.model.MyBatisMapperModelResolver;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 从显式 Spring/Jakarta/JSR-330 注入点导航到 Mapper XML。
 */
public final class MyBatisSpringInjectionLineMarkerProvider
        extends RelatedItemLineMarkerProvider {
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.annotation.Resource",
            "jakarta.annotation.Resource",
            "javax.inject.Inject",
            "jakarta.inject.Inject");

    @Override
    public void collectNavigationMarkers(
            @NotNull PsiElement element,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        if (!(element instanceof PsiIdentifier identifier)
                || !(identifier.getParent() instanceof PsiVariable variable)
                || variable.getNameIdentifier() != identifier) {
            return;
        }
        Project project = variable.getProject();
        if (project.isDisposed() || !project.isOpen() || DumbService.isDumb(project)) {
            return;
        }
        try {
            if (!isExplicitInjectionPoint(variable)) {
                return;
            }
            PsiClass mapper = PsiUtil.resolveClassInClassTypeOnly(variable.getType());
            String namespace = mapper == null ? null : mapper.getQualifiedName();
            if (mapper == null
                    || namespace == null
                    || !(MyBatisMapperModelResolver.resolve(mapper)
                    instanceof MyBatisMapperModelResolution.Found)) {
                return;
            }
            List<XmlTag> targets = MyBatisXmlSymbolLocator.findMapperRoots(
                    project,
                    namespace,
                    variable.getResolveScope());
            if (targets.isEmpty()) {
                return;
            }
            result.add(NavigationGutterIconBuilder
                    .create(AllIcons.Gutter.ImplementedMethod)
                    .setTargets(targets)
                    .setTooltipText(MyBatisAssistantBundle.message(
                            "navigation.spring.injection.to.mapper.xml"))
                    .setPopupTitle(MyBatisAssistantBundle.message(
                            "navigation.spring.injection.target.name",
                            namespace))
                    .createLineMarkerInfo(identifier));
        } catch (IndexNotReadyException ignored) {
            // Smart -> Dumb 竞态时保持静默，不把平台正常状态报告给用户。
        }
    }

    private static boolean isExplicitInjectionPoint(@NotNull PsiVariable variable) {
        ProgressManager.checkCanceled();
        if (hasInjectionAnnotation(variable)) {
            return true;
        }
        if (variable instanceof PsiParameter parameter
                && parameter.getDeclarationScope() instanceof PsiMethod method) {
            return hasInjectionAnnotation(method);
        }
        return false;
    }

    private static boolean hasInjectionAnnotation(@NotNull PsiModifierListOwner owner) {
        for (PsiAnnotation annotation : owner.getAnnotations()) {
            ProgressManager.checkCanceled();
            if (INJECTION_ANNOTATIONS.contains(annotation.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }
}
