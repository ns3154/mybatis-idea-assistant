package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 方法名 SQL 模块的资源化消息入口。
 */
final class MyBatisMethodSqlMessages {
    private MyBatisMethodSqlMessages() {
    }

    static @NotNull String message(@NotNull String key, @NotNull Object... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
