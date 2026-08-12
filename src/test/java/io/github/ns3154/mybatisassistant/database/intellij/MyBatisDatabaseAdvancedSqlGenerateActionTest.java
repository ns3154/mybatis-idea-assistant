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
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisForeignKeyReference;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinGeneration;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisJoinType;
import io.github.ns3154.mybatisassistant.methodsql.MyBatisWrapperFramework;

import java.lang.reflect.Proxy;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class MyBatisDatabaseAdvancedSqlGenerateActionTest
        extends BasePlatformTestCase {
    public void testOptionalDescriptorRegistersWrapperAndJoinActions() {
        ActionManager manager = ActionManager.getInstance();
        AnAction wrapper = manager.getAction(MyBatisDatabaseWrapperGenerateAction.ID);
        AnAction join = manager.getAction(MyBatisDatabaseJoinGenerateAction.ID);

        assertInstanceOf(wrapper, MyBatisDatabaseWrapperGenerateAction.class);
        assertInstanceOf(join, MyBatisDatabaseJoinGenerateAction.class);
        assertEquals("生成 MyBatis Wrapper…", wrapper.getTemplateText());
        assertEquals("生成显式 Join SQL…", join.getTemplateText());
        ActionGroup group = assertInstanceOf(
                manager.getAction("DatabaseViewPopupMenu"), ActionGroup.class);
        List<AnAction> actions = Arrays.asList(group.getChildren(null));
        assertTrue(actions.contains(wrapper));
        assertTrue(actions.contains(join));
    }

    public void testActionUpdatesEnforceTableCountLoadStateAndDataSource() {
        DbDataSource firstSource = dataSource(false);
        DbDataSource secondSource = dataSource(false);
        MyBatisDatabaseWrapperGenerateAction wrapper =
                new MyBatisDatabaseWrapperGenerateAction();
        MyBatisDatabaseJoinGenerateAction join = new MyBatisDatabaseJoinGenerateAction();

        assertTrue(update(wrapper, new DbElement[]{table(firstSource, true)}).isEnabled());
        assertFalse(update(wrapper, new DbElement[]{
                table(firstSource, true), table(firstSource, true)}).isEnabled());
        assertTrue(update(join, new DbElement[]{
                table(firstSource, true), table(firstSource, true)}).isEnabled());
        assertFalse(update(join, new DbElement[]{
                table(firstSource, true), table(secondSource, true)}).isEnabled());
        assertFalse(update(join, new DbElement[]{table(firstSource, true)}).isEnabled());
        assertFalse(update(join, new DbElement[]{
                table(firstSource, true), table(dataSource(true), true)}).isEnabled());
    }

    public void testWrapperPreviewContainsMethodXmlAndFrameworkCode() {
        MyBatisDatabaseWrapperGenerateAction.WrapperPreview preview =
                MyBatisDatabaseWrapperGenerateAction.buildPreview(
                        users(),
                        MyBatisSqlDialect.MYSQL,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        "findNameByRoleIdOrderByIdDesc",
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17");

        assertTrue(preview.methodDeclaration().contains(
                "findNameByRoleIdOrderByIdDesc("));
        assertTrue(preview.xmlStatement().contains(
                "<select id=\"findNameByRoleIdOrderByIdDesc\""));
        assertTrue(preview.wrapperCode().contains(
                "wrapper.select(\"`name`\")"));
        assertTrue(preview.wrapperCode().contains(
                ".eq(\"`role_id`\", roleId)"));
    }

    public void testWrapperPreviewCarriesTheSelectedQualifiedTable() {
        MyBatisDatabaseTable auditUsers = new MyBatisDatabaseTable(
                Optional.of("tenant_catalog"),
                Optional.of("audit"),
                "users",
                users().columns());

        MyBatisDatabaseWrapperGenerateAction.WrapperPreview flex =
                MyBatisDatabaseWrapperGenerateAction.buildPreview(
                        auditUsers,
                        MyBatisSqlDialect.POSTGRESQL,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        "findByRoleId",
                        MyBatisWrapperFramework.MYBATIS_FLEX,
                        "1.11.8");

        assertTrue(flex.xmlStatement().contains("FROM \"audit\".\"users\""));
        assertTrue(flex.wrapperCode().contains(
                "RawQueryTable(\"\\\"audit\\\".\\\"users\\\"\")"));
        assertTrue(org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseWrapperGenerateAction.buildPreview(
                        auditUsers,
                        MyBatisSqlDialect.POSTGRESQL,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        "findByRoleId",
                        MyBatisWrapperFramework.MYBATIS_PLUS,
                        "3.5.17"))
                .getMessage().contains("无法证明"));
    }

    public void testJoinModelRequiresEvidenceAndGeneratesExplicitSelection() {
        MyBatisDatabaseJoinGenerateAction.JoinModel model =
                MyBatisDatabaseJoinGenerateAction.model(
                        users(),
                        roles(),
                        MyBatisSqlDialect.POSTGRESQL,
                        MyBatisGenerationConfiguration.standard("com.example"));

        assertEquals(1, model.relations().size());
        MyBatisJoinGeneration generation =
                MyBatisDatabaseJoinGenerateAction.buildGeneration(
                        model,
                        model.relations().get(0),
                        MyBatisJoinType.LEFT,
                        "t1.id,t1.name,t2.name");
        assertEquals(List.of("t1Id", "t1Name", "t2Name"), generation.outputLabels());
        assertEquals(
                "SELECT \"t1\".\"id\" AS \"t1Id\", "
                        + "\"t1\".\"name\" AS \"t1Name\", "
                        + "\"t2\".\"name\" AS \"t2Name\" FROM \"users\" \"t1\" "
                        + "LEFT JOIN \"roles\" \"t2\" ON \"t1\".\"role_id\" = "
                        + "\"t2\".\"id\"",
                generation.sql());
    }

    public void testJoinUiFiltersDialectAndRejectsInvalidSelections() {
        assertEquals(
                List.of(MyBatisJoinType.INNER, MyBatisJoinType.LEFT),
                MyBatisDatabaseJoinGenerateAction.supportedJoinTypes(
                        MyBatisSqlDialect.SQLITE));
        assertEquals(
                List.of(
                        MyBatisJoinType.INNER,
                        MyBatisJoinType.LEFT,
                        MyBatisJoinType.RIGHT),
                MyBatisDatabaseJoinGenerateAction.supportedJoinTypes(
                        MyBatisSqlDialect.MYSQL));
        MyBatisDatabaseJoinGenerateAction.JoinModel model =
                MyBatisDatabaseJoinGenerateAction.model(
                        users(),
                        roles(),
                        MyBatisSqlDialect.MYSQL,
                        MyBatisGenerationConfiguration.standard("com.example"));

        assertTrue(org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseJoinGenerateAction.buildGeneration(
                        model,
                        model.relations().get(0),
                        MyBatisJoinType.LEFT,
                        "t1.id,t1.id"))
                .getMessage().contains("重复"));
        assertTrue(org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseJoinGenerateAction.model(
                        roles(),
                        unrelated(),
                        MyBatisSqlDialect.MYSQL,
                        MyBatisGenerationConfiguration.standard("com.example")))
                .getMessage().contains("没有可供用户选择"));
    }

    public void testJoinModelRejectsForeignKeyTargetingAnotherSelectedTableIdentity() {
        MyBatisDatabaseTable misleadingUsers = new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "users",
                List.of(
                        column("id", Types.BIGINT, true, false, 0),
                        foreignColumn(
                                "role_id", Types.BIGINT, "archived_roles", "id", 1)));

        assertTrue(org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseJoinGenerateAction.model(
                        misleadingUsers,
                        roles(),
                        MyBatisSqlDialect.POSTGRESQL,
                        MyBatisGenerationConfiguration.standard("com.example")))
                .getMessage().contains("没有可供用户选择"));
    }

    private Presentation update(AnAction action, DbElement[] elements) {
        SimpleDataContext.Builder context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject());
        if (elements != null) {
            context.add(DatabaseView.DB_ELEMENTS, elements);
        }
        Presentation presentation = new Presentation();
        AnActionEvent event = AnActionEvent.createEvent(
                action,
                context.build(),
                presentation,
                "S9 高级生成测试",
                ActionUiKind.NONE,
                null);
        action.update(event);
        return presentation;
    }

    private static MyBatisDatabaseTable users() {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "users",
                List.of(
                        column("id", Types.BIGINT, true, false, 0),
                        foreignColumn(
                                "role_id", Types.BIGINT, "roles", "id", 1),
                        column("name", Types.VARCHAR, false, false, 2)));
    }

    private static MyBatisDatabaseTable roles() {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "roles",
                List.of(
                        column("id", Types.BIGINT, true, false, 0),
                        column("name", Types.VARCHAR, false, false, 1)));
    }

    private static MyBatisDatabaseTable unrelated() {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "audit_log",
                List.of(column("message", Types.VARCHAR, false, false, 0)));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean primary,
            boolean foreign,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                jdbcType,
                true,
                primary,
                foreign,
                false,
                Optional.empty(),
                position);
    }

    private static MyBatisDatabaseColumn foreignColumn(
            String name,
            int jdbcType,
            String targetTable,
            String targetColumn,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                jdbcType,
                true,
                false,
                true,
                false,
                false,
                Optional.empty(),
                position,
                Optional.of(new MyBatisForeignKeyReference(
                        Optional.empty(), Optional.empty(), targetTable, targetColumn)));
    }

    private static DbDataSource dataSource(boolean loading) {
        return proxy(DbDataSource.class, (method, arguments) -> switch (method.getName()) {
            case "isLoading" -> loading;
            case "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DbTable table(DbDataSource source, boolean valid) {
        return proxy(DbTable.class, (method, arguments) -> switch (method.getName()) {
            case "isValid" -> valid;
            case "getDataSource" -> source;
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
