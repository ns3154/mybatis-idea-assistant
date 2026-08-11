package io.github.ns3154.mybatisassistant.platform;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;

public final class MyBatisAssistantPlatformFoundationTest extends BasePlatformTestCase {
    private MyBatisAssistantSettings.SettingsState originalState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalState = MyBatisAssistantSettings.getInstance().getState().copyAndNormalize();
        MyBatisAssistantSettings.getInstance().update(false, false, false);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MyBatisAssistantSettings.getInstance().loadState(originalState);
        } finally {
            super.tearDown();
        }
    }

    public void testExceptionGuardIsolatesRuntimeFailureWithoutLoggingUserInput() {
        boolean completed = MyBatisAssistantExceptionGuard.run(
                getProject(),
                "/Users/example/private/project.sql",
                () -> {
                    throw new IllegalStateException("sensitive SQL and path");
                }
        );

        assertFalse(completed);
    }

    public void testExceptionGuardPropagatesCancellation() {
        try {
            MyBatisAssistantExceptionGuard.run(
                    getProject(),
                    "index.refresh",
                    () -> {
                        throw new ProcessCanceledException();
                    }
            );
            fail("取消异常必须继续传播");
        } catch (ProcessCanceledException expected) {
            // 取消属于控制流，守门不得吞掉。
        }
    }

    public void testNotificationUsesRegisteredGroupAndLocalizedSafeContent() {
        var notification = MyBatisAssistantNotifications.createUnexpectedErrorNotification();

        assertEquals(MyBatisAssistantNotifications.GROUP_ID, notification.getGroupId());
        assertEquals(NotificationType.WARNING, notification.getType());
        assertEquals("MyBatis Assistant 操作未完成", notification.getTitle());
        assertTrue(notification.getContent().contains("未修改项目文件"));
        assertFalse(notification.getContent().contains(getProject().getBasePath()));
    }
}
