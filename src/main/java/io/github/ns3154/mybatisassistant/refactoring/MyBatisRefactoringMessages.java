package io.github.ns3154.mybatisassistant.refactoring;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

/**
 * MyBatis 重构安全检查的资源化消息入口。
 */
final class MyBatisRefactoringMessages {
    private MyBatisRefactoringMessages() {
    }

    static @NotNull String message(@NotNull String key, @NotNull Object... params) {
        return MyBatisAssistantBundle.message(key, params);
    }
}
