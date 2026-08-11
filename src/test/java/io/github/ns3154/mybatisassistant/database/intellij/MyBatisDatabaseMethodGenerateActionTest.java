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
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationArtifactKind;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlanEntry;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationTemplateGroup;

import java.lang.reflect.Proxy;
import java.sql.Types;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MyBatisDatabaseMethodGenerateActionTest extends BasePlatformTestCase {
    public void testOptionalDescriptorRegistersMethodActionInDatabasePopup() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisDatabaseMethodGenerateAction.ID);

        assertInstanceOf(action, MyBatisDatabaseMethodGenerateAction.class);
        assertEquals("按方法名生成 MyBatis SQL…", action.getTemplateText());
        ActionGroup group = assertInstanceOf(
                manager.getAction("DatabaseViewPopupMenu"), ActionGroup.class);
        assertTrue(Arrays.asList(group.getChildren(null)).contains(action));
    }

    public void testUpdateRequiresExactlyOneValidLoadedTable() {
        MyBatisDatabaseMethodGenerateAction action =
                new MyBatisDatabaseMethodGenerateAction();
        assertFalse(update(action, null).isEnabled());
        assertTrue(update(action, new DbElement[]{table(true, false)}).isEnabled());
        assertFalse(update(action, new DbElement[]{
                table(true, false), table(true, false)}).isEnabled());
        assertFalse(update(action, new DbElement[]{table(false, false)}).isEnabled());
        assertFalse(update(action, new DbElement[]{table(true, true)}).isEnabled());
    }

    public void testBuildPlanContainsAtomicMapperAndXmlMethodRegions() throws Exception {
        VirtualFile root = myFixture.getTempDirFixture().findOrCreateDir("method-action-root");

        MyBatisGenerationPlan plan = MyBatisDatabaseMethodGenerateAction.buildPlan(
                getProject(),
                root,
                model(),
                MyBatisSqlDialect.POSTGRESQL,
                MyBatisGenerationConfiguration.standard("com.example"),
                "findByNameAndAgeGreaterThanOrderByIdDesc");

        assertFalse(describe(plan), plan.hasConflicts());
        assertTrue(proposed(plan, MyBatisGenerationArtifactKind.MAPPER)
                .contains("findByNameAndAgeGreaterThanOrderByIdDesc("));
        assertTrue(proposed(plan, MyBatisGenerationArtifactKind.XML)
                .contains("<select id=\"findByNameAndAgeGreaterThanOrderByIdDesc\""));
        assertTrue(plan.entries().stream().allMatch(entry ->
                entry.status() == io.github.ns3154.mybatisassistant.generator
                        .MyBatisGenerationPlanStatus.CREATE));
    }

    public void testBuildPlanRejectsInvalidMethodAtPosition() throws Exception {
        VirtualFile root = myFixture.getTempDirFixture().findOrCreateDir("invalid-method-root");

        IllegalArgumentException failure = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseMethodGenerateAction.buildPlan(
                        getProject(),
                        root,
                        model(),
                        MyBatisSqlDialect.GENERIC,
                        MyBatisGenerationConfiguration.standard("com.example"),
                        "findByMissing"));

        assertTrue(failure.getMessage().contains("位置 4"));
    }

    public void testBuildPlanRequiresMapperAndXmlArtifacts() throws Exception {
        VirtualFile root = myFixture.getTempDirFixture().findOrCreateDir("missing-target-root");
        MyBatisGenerationConfiguration incomplete = new MyBatisGenerationConfiguration(
                "com.example",
                "src/main/java",
                "src/main/resources",
                EnumSet.of(MyBatisGenerationArtifactKind.MAPPER),
                MyBatisGenerationTemplateGroup.STANDARD,
                "",
                "",
                true,
                true,
                Set.of(),
                Map.of());

        IllegalArgumentException failure = org.junit.Assert.assertThrows(
                IllegalArgumentException.class,
                () -> MyBatisDatabaseMethodGenerateAction.buildPlan(
                        getProject(), root, model(), MyBatisSqlDialect.GENERIC,
                        incomplete, "findByName"));

        assertTrue(failure.getMessage().contains("同时选择 Mapper 与 XML"));
    }

    private Presentation update(
            MyBatisDatabaseMethodGenerateAction action,
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
                "S9 测试",
                ActionUiKind.NONE,
                null);
        action.update(event);
        return presentation;
    }

    private static MyBatisDatabaseTable model() {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.empty(),
                "user",
                List.of(
                        column("id", Types.BIGINT, true, 0),
                        column("name", Types.VARCHAR, false, 1),
                        column("age", Types.INTEGER, false, 2)));
    }

    private static MyBatisDatabaseColumn column(
            String name,
            int jdbcType,
            boolean primary,
            int position) {
        return new MyBatisDatabaseColumn(
                name,
                "TYPE",
                jdbcType,
                true,
                primary,
                false,
                false,
                Optional.empty(),
                position);
    }

    private String proposed(MyBatisGenerationPlan plan, MyBatisGenerationArtifactKind kind) {
        return entry(plan, kind).proposedText().orElseThrow();
    }

    private MyBatisGenerationPlanEntry entry(
            MyBatisGenerationPlan plan,
            MyBatisGenerationArtifactKind kind) {
        return plan.entries().stream()
                .filter(candidate -> candidate.artifact().kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private String describe(MyBatisGenerationPlan plan) {
        return plan.entries().stream()
                .filter(entry -> entry.message().isPresent())
                .map(entry -> entry.message().orElseThrow())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static DbTable table(boolean valid, boolean loading) {
        DbDataSource source = proxy(DbDataSource.class, (method, arguments) -> switch (
                method.getName()) {
            case "isLoading" -> loading;
            case "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
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
