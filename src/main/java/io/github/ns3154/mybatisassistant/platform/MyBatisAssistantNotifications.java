package io.github.ns3154.mybatisassistant.platform;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;
import org.jetbrains.annotations.NotNull;

public final class MyBatisAssistantNotifications {
    public static final String GROUP_ID = "MyBatis Assistant";

    private MyBatisAssistantNotifications() {
    }

    public static void notifyUnexpectedError(@NotNull Project project) {
        if (project.isDisposed() || !MyBatisAssistantSettings.getInstance().isShowNotifications()) {
            return;
        }
        createUnexpectedErrorNotification().notify(project);
    }

    static @NotNull Notification createUnexpectedErrorNotification() {
        return new Notification(
                GROUP_ID,
                MyBatisAssistantBundle.message("notification.unexpected.error.title"),
                MyBatisAssistantBundle.message("notification.unexpected.error.content"),
                NotificationType.WARNING
        );
    }
}
