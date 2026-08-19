package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;

public final class MyBatisLogSqlRestoreActionTest extends BasePlatformTestCase {
    public void testMainDescriptorRegistersActionInToolsMenu() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisLogSqlRestoreAction.ID);

        assertInstanceOf(action, MyBatisLogSqlRestoreAction.class);
        assertEquals("还原 MyBatis 日志 SQL…", action.getTemplateText());
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
        ActionGroup tools = assertInstanceOf(manager.getAction("ToolsMenu"), ActionGroup.class);
        assertTrue(Arrays.asList(tools.getChildren(null)).contains(action));
    }
}
