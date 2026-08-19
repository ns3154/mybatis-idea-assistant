package io.github.ns3154.mybatisassistant.model;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 统一语义模型的资源化消息入口。
 */
final class MyBatisModelMessages {
    private MyBatisModelMessages() {
    }

    static @NotNull String message(@NotNull String key, @NotNull Object... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
