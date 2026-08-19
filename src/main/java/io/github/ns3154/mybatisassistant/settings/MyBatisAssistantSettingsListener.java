package io.github.ns3154.mybatisassistant.settings;

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/**
 * 设置原子替换后的进程内通知。
 */
public interface MyBatisAssistantSettingsListener {
    Topic<MyBatisAssistantSettingsListener> TOPIC = Topic.create(
            "MyBatis Assistant settings changed",
            MyBatisAssistantSettingsListener.class);

    void settingsChanged(@NotNull MyBatisAssistantSettings.SettingsState state);
}
