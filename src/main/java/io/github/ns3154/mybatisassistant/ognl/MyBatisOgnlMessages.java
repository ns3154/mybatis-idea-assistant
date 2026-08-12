package io.github.ns3154.mybatisassistant.ognl;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * OGNL 词法、语法与语义分析的资源化消息入口。
 */
final class MyBatisOgnlMessages {
    private MyBatisOgnlMessages() {
    }

    static @NotNull String message(@NotNull String key, @NotNull Object... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
