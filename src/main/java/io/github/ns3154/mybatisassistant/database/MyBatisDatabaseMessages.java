package io.github.ns3154.mybatisassistant.database;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * 数据库模型、元数据与 Community JDBC 的资源化消息入口。
 */
public final class MyBatisDatabaseMessages {
    private MyBatisDatabaseMessages() {
    }

    public static @NotNull String message(@NotNull String key, Object @NotNull ... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
