package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.Dbms;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasModel;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.types.DasBuiltinType;
import com.intellij.database.types.DasBuiltinTypeClass;
import com.intellij.database.types.DasTypeCategory;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.containers.JBTreeTraverser;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataProvider;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.time.Duration;
import java.lang.reflect.Proxy;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DatabaseToolsMetadataProviderTest extends BasePlatformTestCase {
    public void testOptionalDescriptorRegistersProvider() {
        var providers = MyBatisDatabaseMetadataProvider.EP_NAME.getExtensionList().stream()
                .filter(provider -> "jetbrains-database-tools".equals(provider.id()))
                .toList();

        assertEquals(1, providers.size());
        assertInstanceOf(providers.getFirst(), DatabaseToolsMetadataProvider.class);
    }

    public void testOptionalDescriptorRegistersControlledSqlExecutionAction() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisDatabaseSqlExecuteAction.ID);

        assertInstanceOf(action, MyBatisDatabaseSqlExecuteAction.class);
        assertEquals("受控执行 SQL…", action.getTemplateText());
        ActionGroup group = assertInstanceOf(
                manager.getAction("DatabaseViewPopupMenu"), ActionGroup.class);
        assertContainsElements(Arrays.asList(group.getChildren(null)), action);
    }

    public void testMapsPublicDbmsFamiliesWithoutGuessingUnknown() {
        assertEquals(MyBatisSqlDialect.MYSQL,
                DatabaseToolsMetadataProvider.dialect(Dbms.MYSQL));
        assertEquals(MyBatisSqlDialect.POSTGRESQL,
                DatabaseToolsMetadataProvider.dialect(Dbms.POSTGRES));
        assertEquals(MyBatisSqlDialect.ORACLE,
                DatabaseToolsMetadataProvider.dialect(Dbms.ORACLE));
        assertEquals(MyBatisSqlDialect.SQL_SERVER,
                DatabaseToolsMetadataProvider.dialect(Dbms.MSSQL));
        assertEquals(MyBatisSqlDialect.SQLITE,
                DatabaseToolsMetadataProvider.dialect(Dbms.SQLITE));
        assertEquals(MyBatisSqlDialect.H2,
                DatabaseToolsMetadataProvider.dialect(Dbms.H2));
        assertEquals(MyBatisSqlDialect.GENERIC,
                DatabaseToolsMetadataProvider.dialect(Dbms.UNKNOWN));
    }

    public void testDatabaseModelChangeInvalidatesCompletedSnapshot() throws Exception {
        MyBatisDatabaseMetadataProvider.EP_NAME.getPoint().registerExtension(
                new MyBatisDatabaseMetadataProvider() {
                    @Override
                    public String id() {
                        return "invalidation-fixture";
                    }

                    @Override
                    public List<MyBatisDatabaseSnapshot> load(
                            Project project,
                            MyBatisDatabaseRequest request,
                            ProgressIndicator indicator) {
                        return List.of(new MyBatisDatabaseSnapshot(
                                "main",
                                "Main",
                                MyBatisSqlDialect.GENERIC,
                                MyBatisMetadataFreshness.READY,
                                1,
                                List.of()));
                    }
                },
                getTestRootDisposable());
        MyBatisDatabaseMetadataService service = MyBatisDatabaseMetadataService
                .getInstance(getProject());
        service.load(MyBatisDatabaseRequest.all(), Duration.ofSeconds(1))
                .get(2, TimeUnit.SECONDS);
        assertTrue(service.latest().isPresent());

        getProject().getMessageBus().syncPublisher(DbPsiFacade.TOPIC).onChanged(null);

        assertTrue(service.latest().isEmpty());
    }

    public void testSnapshotPreservesNamespacesKeysTypesOrderAndFreshness() {
        DasObject catalog = object("catalog", ObjectKind.DATABASE, null);
        DasObject schema = object("public", ObjectKind.SCHEMA, catalog);
        DasColumn userId = column(
                "user_id", 2, schema, DasTypeCategory.STRING, "VARCHAR", false);
        DasColumn id = column(
                "id", 1, schema, DasTypeCategory.INTEGER, "BIGINT", true);
        DasTable table = proxy(DasTable.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getName" -> "users";
            case "getKind" -> ObjectKind.TABLE;
            case "getDasParent" -> schema;
            case "getDasChildren" -> arguments[0] == ObjectKind.COLUMN
                    ? JBIterable.of(userId, id)
                    : JBIterable.empty();
            case "getColumnAttrs" -> arguments[0] == id
                    ? Set.of(DasColumn.Attribute.PRIMARY_KEY)
                    : Set.of(DasColumn.Attribute.FOREIGN_KEY);
            case "isSystem", "isTemporary", "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
        DasModel model = proxy(DasModel.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "traverser" -> JBTreeTraverser.<DasObject>from(value -> List.of())
                    .withRoot(table);
            default -> defaultValue(method.getReturnType());
        });
        DbDataSource dataSource = proxy(DbDataSource.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getUniqueId" -> "main";
            case "getName" -> "Main";
            case "getDbms" -> Dbms.MYSQL;
            case "getModel" -> model;
            case "isLoading" -> false;
            case "getModificationTracker" -> (ModificationTracker) () -> 7;
            default -> defaultValue(method.getReturnType());
        });

        MyBatisDatabaseSnapshot snapshot = DatabaseToolsMetadataProvider.snapshot(
                dataSource,
                new EmptyProgressIndicator());

        assertEquals("main", snapshot.dataSourceId());
        assertEquals(MyBatisSqlDialect.MYSQL, snapshot.dialect());
        assertEquals(MyBatisMetadataFreshness.READY, snapshot.freshness());
        assertEquals(7, snapshot.modificationCount());
        assertSize(1, snapshot.tables());
        assertEquals(Optional.of("catalog"), snapshot.tables().getFirst().catalog());
        assertEquals(Optional.of("public"), snapshot.tables().getFirst().schema());
        assertEquals(
                List.of("id", "user_id"),
                snapshot.tables().getFirst().columns().stream()
                        .map(MyBatisDatabaseColumn::name)
                        .toList());
        MyBatisDatabaseColumn primary = snapshot.tables().getFirst().columns().getFirst();
        assertEquals(Types.BIGINT, primary.jdbcType());
        assertTrue(primary.primaryKey());
        assertFalse(primary.nullable());
        MyBatisDatabaseColumn foreign = snapshot.tables().getFirst().columns().get(1);
        assertEquals(Types.VARCHAR, foreign.jdbcType());
        assertTrue(foreign.foreignKey());
        assertTrue(foreign.nullable());
    }

    private static DasObject object(String name, ObjectKind kind, DasObject parent) {
        return proxy(DasObject.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getKind" -> kind;
            case "getDasParent" -> parent;
            case "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DasColumn column(
            String name,
            int position,
            DasObject parent,
            DasTypeCategory category,
            String specification,
            boolean notNull) {
        DasBuiltinTypeClass<?> typeClass = proxy(
                DasBuiltinTypeClass.class,
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getCategory" -> category;
                    case "getName" -> specification;
                    case "getCanonical" -> ignored;
                    default -> defaultValue(method.getReturnType());
                });
        DasBuiltinType<?> type = proxy(
                DasBuiltinType.class,
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "getTypeClass" -> typeClass;
                    case "getSpecification", "getDescription" -> specification;
                    case "withTypeClass" -> ignored;
                    default -> defaultValue(method.getReturnType());
                });
        return proxy(DasColumn.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getName" -> name;
            case "getKind" -> ObjectKind.COLUMN;
            case "getDasParent" -> parent;
            case "getDasType" -> type;
            case "getPosition" -> (short) position;
            case "isNotNull" -> notNull;
            case "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == arguments[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return type.getSimpleName() + "Fixture";
                    }
                    return handler.invoke(proxy, method, arguments);
                });
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
        return Arrays.stream(new Object[]{
                (byte) 0, (short) 0, 0, 0L, 0F, 0D})
                .filter(value -> primitiveWrapper(type).isInstance(value))
                .findFirst()
                .orElse(0);
    }

    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Integer.class;
    }
}
