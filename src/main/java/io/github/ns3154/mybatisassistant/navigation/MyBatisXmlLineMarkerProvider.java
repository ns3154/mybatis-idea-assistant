package io.github.ns3154.mybatisassistant.navigation;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.resolve.MyBatisMapperMethodResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public final class MyBatisXmlLineMarkerProvider extends RelatedItemLineMarkerProvider {
    static final String TOOLTIP_TEXT = MyBatisAssistantBundle.message(
            "navigation.to.mapper.method");

    @Override
    public void collectNavigationMarkers(
            @NotNull PsiElement element,
            @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        StatementReference reference = statementReference(element);
        if (reference == null) {
            return;
        }

        List<PsiMethod> targets = MyBatisMapperMethodResolver.find(
                element,
                reference.namespace(),
                reference.statementId());
        if (targets.isEmpty()) {
            return;
        }

        result.add(NavigationGutterIconBuilder
                .create(AllIcons.Gutter.ImplementingMethod)
                .setTargets(targets)
                .setTooltipText(TOOLTIP_TEXT)
                .setPopupTitle(MyBatisAssistantBundle.message(
                        "navigation.mapper.target.name",
                        reference.namespace(),
                        reference.statementId()))
                .createLineMarkerInfo(element));
    }

    static @NotNull List<PsiMethod> findTargets(@NotNull PsiElement element) {
        StatementReference reference = statementReference(element);
        if (reference == null) {
            return List.of();
        }
        return MyBatisMapperMethodResolver.find(
                element,
                reference.namespace(),
                reference.statementId());
    }

    private static @Nullable StatementReference statementReference(@NotNull PsiElement element) {
        if (!(element instanceof XmlToken token)
                || token.getTokenType() != XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
                || !(token.getParent() instanceof XmlAttributeValue attributeValue)
                || !(attributeValue.getParent() instanceof XmlAttribute attribute)
                || !"id".equals(attribute.getName())) {
            return null;
        }

        XmlTag statementTag = attribute.getParent();
        XmlTag mapperTag = statementTag.getParentTag();
        if (!MyBatisXmlModel.isStatement(statementTag)
                || mapperTag == null
                || mapperTag.getParentTag() != null
                || !MyBatisXmlModel.isMapperRoot(mapperTag)) {
            return null;
        }

        String namespace = MyBatisXmlModel.namespace(mapperTag);
        String statementId = MyBatisXmlModel.statementId(statementTag);
        if (namespace == null || statementId == null) {
            return null;
        }
        return new StatementReference(namespace, statementId);
    }

    private record StatementReference(@NotNull String namespace, @NotNull String statementId) {
    }
}
