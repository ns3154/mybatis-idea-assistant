package io.github.ns3154.mybatisassistant;

import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.Locale;
import java.util.ResourceBundle;

public final class MyBatisAssistantBundle extends DynamicBundle {
    private static final String BUNDLE = "messages.MyBatisAssistantBundle";
    private static final MyBatisAssistantBundle INSTANCE = new MyBatisAssistantBundle();

    private MyBatisAssistantBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(
            @PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
            Object @NotNull ... params) {
        var application = ApplicationManager.getApplication();
        if (application != null && !application.isDisposed()) {
            String configuredLocale = MyBatisAssistantSettings.getInstance().getUiLocale();
            if (!configuredLocale.isEmpty()) {
                var bundle = ResourceBundle.getBundle(
                        BUNDLE,
                        Locale.forLanguageTag(configuredLocale),
                        MyBatisAssistantBundle.class.getClassLoader(),
                        ResourceBundle.Control.getNoFallbackControl(
                                ResourceBundle.Control.FORMAT_PROPERTIES));
                String pattern = bundle.getString(key);
                return params.length == 0
                        ? pattern
                        : new java.text.MessageFormat(
                                pattern, Locale.forLanguageTag(configuredLocale)).format(params);
            }
        }
        return INSTANCE.getMessage(key, params);
    }
}
