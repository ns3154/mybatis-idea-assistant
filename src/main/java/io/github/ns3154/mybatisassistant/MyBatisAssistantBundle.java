package io.github.ns3154.mybatisassistant;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class MyBatisAssistantBundle extends DynamicBundle {
    private static final String BUNDLE = "messages.MyBatisAssistantBundle";
    private static final MyBatisAssistantBundle INSTANCE = new MyBatisAssistantBundle();

    private MyBatisAssistantBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(
            @PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
            Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
