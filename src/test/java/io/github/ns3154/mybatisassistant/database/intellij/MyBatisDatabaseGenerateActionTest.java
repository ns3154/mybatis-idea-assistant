package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbTable;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.lang.reflect.Proxy;
import java.util.Arrays;

public final class MyBatisDatabaseGenerateActionTest extends BasePlatformTestCase {
    public void testOptionalDescriptorRegistersActionInDatabasePopup() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisDatabaseGenerateAction.ID);

        assertInstanceOf(action, MyBatisDatabaseGenerateAction.class);
        assertEquals("生成 MyBatis 代码…", action.getTemplateText());
        ActionGroup group = assertInstanceOf(
                manager.getAction("DatabaseViewPopupMenu"), ActionGroup.class);
        assertTrue("数据库右键菜单必须包含生成入口",
                Arrays.asList(group.getChildren(null)).contains(action));
    }

    public void testUpdateRequiresOnlyValidLoadedTables() {
        MyBatisDatabaseGenerateAction action = new MyBatisDatabaseGenerateAction();
        Presentation noSelection = update(action, null);
        assertFalse(noSelection.isEnabled());
        assertFalse(noSelection.isVisible());

        Presentation valid = update(action, new DbElement[]{table(true, false)});
        assertTrue(valid.isEnabled());
        assertTrue(valid.isVisible());

        Presentation loading = update(action, new DbElement[]{table(true, true)});
        assertFalse(loading.isEnabled());
        assertFalse(loading.isVisible());

        Presentation invalid = update(action, new DbElement[]{table(false, false)});
        assertFalse(invalid.isEnabled());
        assertFalse(invalid.isVisible());

        Presentation mixed = update(action, new DbElement[]{
                table(true, false), element()});
        assertFalse(mixed.isEnabled());
        assertFalse(mixed.isVisible());
    }

    private Presentation update(
            MyBatisDatabaseGenerateAction action,
            DbElement[] elements) {
        SimpleDataContext.Builder builder = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject());
        if (elements != null) {
            builder.add(DatabaseView.DB_ELEMENTS, elements);
        }
        Presentation presentation = new Presentation();
        AnActionEvent event = AnActionEvent.createEvent(
                action,
                builder.build(),
                presentation,
                "S8 测试",
                ActionUiKind.NONE,
                null);
        action.update(event);
        return presentation;
    }

    private static DbTable table(boolean valid, boolean loading) {
        DbDataSource source = proxy(DbDataSource.class, (method, arguments) -> switch (
                method.getName()) {
            case "isLoading" -> loading;
            case "isValid" -> true;
            case "toString" -> "S8 数据源";
            default -> defaultValue(method.getReturnType());
        });
        return proxy(DbTable.class, (method, arguments) -> switch (method.getName()) {
            case "isValid" -> valid;
            case "getDataSource" -> source;
            case "toString" -> "S8 表";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DbElement element() {
        return proxy(DbElement.class, (method, arguments) -> switch (method.getName()) {
            case "isValid" -> true;
            case "toString" -> "非表元素";
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
