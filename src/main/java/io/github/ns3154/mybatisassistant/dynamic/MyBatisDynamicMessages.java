package io.github.ns3154.mybatisassistant.dynamic;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 动态 SQL 编译与来源映射的资源化消息入口。
 */
final class MyBatisDynamicMessages {
    private MyBatisDynamicMessages() {
    }

    static @NotNull String message(@NotNull String key, Object @NotNull ... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
