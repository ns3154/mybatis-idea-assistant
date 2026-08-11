package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * 仅向 MyBatis 动态标签的 OGNL 属性注入语言。
 */
public final class MyBatisOgnlXmlInjector implements MultiHostInjector {
    private static final Set<String> CONDITION_TAGS = Set.of("if", "when");

    @Override
    public void getLanguagesToInject(
            @NotNull MultiHostRegistrar registrar,
            @NotNull PsiElement context) {
        ProgressManager.checkCanceled();
        if (!(context instanceof XmlAttributeValue value)
                || !(value.getParent() instanceof XmlAttribute attribute)
                || !(attribute.getParent() instanceof XmlTag tag)
                || !(value instanceof PsiLanguageInjectionHost host)
                || !host.isValidHost()
                || !isOgnlAttribute(tag, attribute)
                || !isInsideMapper(tag)) {
            return;
        }
        TextRange range = value.getValueTextRange()
                .shiftLeft(value.getTextRange().getStartOffset());
        registrar.startInjecting(MyBatisOgnlLanguage.INSTANCE)
                .addPlace(null, null, host, range)
                .doneInjecting();
    }

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(XmlAttributeValue.class);
    }

    private static boolean isOgnlAttribute(
            @NotNull XmlTag tag,
            @NotNull XmlAttribute attribute) {
        if (attribute.getName().indexOf(':') >= 0 || tag.getName().indexOf(':') >= 0) {
            return false;
        }
        return CONDITION_TAGS.contains(tag.getName()) && "test".equals(attribute.getName())
                || "bind".equals(tag.getName()) && "value".equals(attribute.getName());
    }

    private static boolean isInsideMapper(@NotNull XmlTag tag) {
        XmlTag current = tag;
        while (current.getParentTag() != null) {
            ProgressManager.checkCanceled();
            current = current.getParentTag();
        }
        return MyBatisXmlModel.isMapperRoot(current);
    }
}
