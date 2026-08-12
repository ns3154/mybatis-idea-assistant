package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.Dbms;
import com.intellij.database.dialects.DatabaseDialects;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasModel;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.psi.DataSourceManager;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.psi.ModelNameIndex;
import com.intellij.database.types.DasBuiltinType;
import com.intellij.database.types.DasBuiltinTypeClass;
import com.intellij.database.types.DasTypeCategory;
import com.intellij.database.util.Casing;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlToken;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.containers.JBTreeTraverser;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataResult;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseRequest;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MyBatisDatabaseObjectReferenceContributorTest
        extends BasePlatformTestCase {
    public void testRegisteredReferenceResolvesTableAndColumnDatabasePsi()
            throws Exception {
        DatabaseFixture fixture = installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id", "name"))));
        warmMetadata();

        configure("select u.i<caret>d from public.users u where u.id = #{id}");
        MyBatisDatabaseObjectReference columnReference = referenceAtCaret();

        assertEquals("id", columnReference.getCanonicalText());
        assertTrue(columnReference.isSoft());
        assertSame(fixture.element("public.users.id"), columnReference.resolve());
        assertTrue(columnReference.isReferenceTo(fixture.element("public.users.id")));

        configure("select * from public.us<caret>ers");
        MyBatisDatabaseObjectReference tableReference = referenceAtCaret();

        assertEquals("users", tableReference.getCanonicalText());
        assertSame(fixture.element("public.users"), tableReference.resolve());
    }

    public void testDatabasePsiFindUsagesFindsMapperSqlReference() throws Exception {
        DatabaseFixture fixture = installDatabase(false, List.of(
                new TableFixture("public", "USERS", List.of("USER_ID"))));
        warmMetadata();
        configure("select user_<caret>id from public.users");
        PsiElement column = fixture.element("public.USERS.USER_ID");
        assertSame(column, referenceAtCaret().resolve());

        List<PsiReference> usages = new ArrayList<>(ReferencesSearch.search(
                column,
                GlobalSearchScope.projectScope(getProject())).findAll());

        assertTrue(usages.toString(), usages.stream()
                .anyMatch(reference -> reference instanceof MyBatisDatabaseObjectReference
                        && "user_id".equals(reference.getCanonicalText())
                        && reference.isReferenceTo(column)));
    }

    public void testDynamicNestedSqlKeepsExactSourceRange() throws Exception {
        DatabaseFixture fixture = installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id", "active"))));
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select u.id from public.users u
                        <where><if test="enabled">u.act<caret>ive = #{enabled}</if></where>
                    </select>
                </mapper>
                """);

        MyBatisDatabaseObjectReference reference = referenceAtCaret();

        assertEquals("active", reference.getCanonicalText());
        assertSame(fixture.element("public.users.active"), reference.resolve());
    }

    public void testCdataSqlKeepsExactSourceRange() throws Exception {
        DatabaseFixture fixture = installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id"))));
        warmMetadata();
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find"><![CDATA[
                        select i<caret>d from public.users
                    ]]></select>
                </mapper>
                """);

        MyBatisDatabaseObjectReference reference = referenceAtCaret();

        assertEquals("id", reference.getCanonicalText());
        assertSame(fixture.element("public.users.id"), reference.resolve());
    }

    public void testExistingReferenceStopsResolvingAfterDatabaseModelChanges()
            throws Exception {
        DatabaseFixture fixture = installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id"))));
        warmMetadata();
        configure("select i<caret>d from public.users");
        MyBatisDatabaseObjectReference reference = referenceAtCaret();
        assertNotNull(reference.resolve());

        fixture.advanceModel();

        assertNull(reference.resolve());
        MyBatisDatabaseObjectReference cachedReference = databaseReferenceAtCaret();
        assertNotNull(cachedReference);
        assertTrue(cachedReference.isSoft());
        assertNull(cachedReference.resolve());
    }

    public void testAmbiguousMissingDynamicAndLoadingSourcesStaySilent()
            throws Exception {
        installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id")),
                new TableFixture("archive", "users", List.of("id"))));
        warmMetadata();

        configure("select <caret>id from users");
        assertNull(databaseReferenceAtCaret());

        configure("select * from ${ta<caret>ble}");
        assertNull(databaseReferenceAtCaret());

        configure("select <caret>missing from public.users");
        assertNull(databaseReferenceAtCaret());

        installDatabase(true, List.of(
                new TableFixture("public", "users", List.of("id"))));
        MyBatisDatabaseMetadataService.getInstance(getProject()).invalidate();
        warmMetadata();
        configure("select <caret>id from public.users");
        assertNull(databaseReferenceAtCaret());
    }

    public void testDumbModeIsSilentAndCancellationPropagates() throws Exception {
        installDatabase(false, List.of(
                new TableFixture("public", "users", List.of("id"))));
        warmMetadata();
        configure("select i<caret>d from public.users");
        XmlToken token = tokenAtCaret();

        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () ->
                assertEmpty(databaseReferences(token)));

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return databaseReferences(token);
                    },
                    indicator);
            fail("取消后的数据库对象引用解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，不能转成空引用。
        }
    }

    private DatabaseFixture installDatabase(
            boolean loading,
            List<TableFixture> definitions) {
        DatabaseFixture fixture = DatabaseFixture.create(
                getProject(),
                loading,
                definitions);
        DbPsiFacade previous = DbPsiFacade.getInstance(getProject());
        assertNotNull(previous);
        ServiceContainerUtil.replaceService(
                getProject(),
                DbPsiFacade.class,
                fixture.facade(),
                getTestRootDisposable());
        return fixture;
    }

    private void warmMetadata() throws Exception {
        MyBatisDatabaseMetadataResult result = MyBatisDatabaseMetadataService
                .getInstance(getProject())
                .load(MyBatisDatabaseRequest.all(), Duration.ofSeconds(2))
                .get(3, TimeUnit.SECONDS);
        assertInstanceOf(result, MyBatisDatabaseMetadataResult.Loaded.class);
    }

    private PsiFile configure(String sql) {
        myFixture.configureByText("UserMapper.xml", """
                <mapper namespace="com.example.UserMapper">
                    <select id="find">%s</select>
                </mapper>
                """.formatted(sql));
        return myFixture.getFile();
    }

    private MyBatisDatabaseObjectReference referenceAtCaret() {
        MyBatisDatabaseObjectReference reference = databaseReferenceAtCaret();
        assertNotNull("光标位置必须存在 Database Tools 表列引用", reference);
        return reference;
    }

    private MyBatisDatabaseObjectReference databaseReferenceAtCaret() {
        int caret = hostCaretOffset();
        XmlToken token = tokenAtCaret();
        return databaseReferences(token).stream()
                .filter(reference -> reference.getRangeInElement()
                        .shiftRight(token.getTextRange().getStartOffset())
                        .containsOffset(caret))
                .findFirst()
                .orElse(null);
    }

    private XmlToken tokenAtCaret() {
        int caret = hostCaretOffset();
        Document document = myFixture.getEditor().getDocument();
        if (document instanceof DocumentWindow window) {
            document = window.getDelegate();
        }
        PsiFile hostFile = PsiDocumentManager.getInstance(getProject())
                .getPsiFile(document);
        assertNotNull(hostFile);
        var leafNode = hostFile.getNode().findLeafElementAt(caret);
        if (leafNode == null && caret > 0) {
            leafNode = hostFile.getNode().findLeafElementAt(caret - 1);
        }
        assertNotNull(leafNode);
        PsiElement leaf = leafNode.getPsi();
        XmlToken token = leaf instanceof XmlToken xmlToken ? xmlToken : null;
        assertNotNull(
                "光标叶节点不是 XmlToken：psi=" + leaf.getClass().getName()
                        + "，elementType=" + leafNode.getElementType()
                        + "，text=" + leafNode.getText(),
                token);
        return token;
    }

    private int hostCaretOffset() {
        int caret = myFixture.getEditor().getCaretModel().getOffset();
        Document document = myFixture.getEditor().getDocument();
        return document instanceof DocumentWindow window
                ? window.injectedToHost(caret)
                : caret;
    }

    private static List<MyBatisDatabaseObjectReference> databaseReferences(
            XmlToken token) {
        return Arrays.stream(token.getReferences())
                .filter(MyBatisDatabaseObjectReference.class::isInstance)
                .map(MyBatisDatabaseObjectReference.class::cast)
                .toList();
    }

    private record TableFixture(String schema, String name, List<String> columns) {
    }

    private record DatabaseFixture(
            DbPsiFacade facade,
            Map<String, PsiElement> elements,
            AtomicLong modificationCount) {
        private PsiElement element(String key) {
            PsiElement result = elements.get(key);
            assertNotNull("缺少数据库 PSI fixture：" + key, result);
            return result;
        }

        private void advanceModel() {
            modificationCount.incrementAndGet();
        }

        private static DatabaseFixture create(
                com.intellij.openapi.project.Project project,
                boolean loading,
                List<TableFixture> definitions) {
            List<DasTable> tables = new ArrayList<>();
            Map<DasObject, PsiElement> psiByObject = new IdentityHashMap<>();
            Map<String, PsiElement> psiByKey = new java.util.LinkedHashMap<>();
            AtomicLong modificationCount = new AtomicLong(1);
            DbDataSource[] sourceHolder = new DbDataSource[1];
            for (TableFixture definition : definitions) {
                DasObject schema = object(definition.schema(), ObjectKind.SCHEMA, null);
                DasTable[] tableHolder = new DasTable[1];
                List<DasColumn> columns = definition.columns().stream()
                        .map(name -> column(name, tableHolder))
                        .toList();
                DasTable table = proxy(DasTable.class, (ignored, method, arguments) -> switch (
                        method.getName()) {
                    case "getName" -> definition.name();
                    case "getKind" -> ObjectKind.TABLE;
                    case "getDasParent" -> schema;
                    case "getDasChildren" -> arguments[0] == ObjectKind.COLUMN
                            ? JBIterable.from(columns)
                            : JBIterable.empty();
                    case "getColumnAttrs" -> java.util.Set.of();
                    case "isSystem", "isTemporary", "isQuoted" -> false;
                    default -> defaultValue(method.getReturnType());
                });
                tableHolder[0] = table;
                tables.add(table);
                String tableKey = definition.schema() + "." + definition.name();
                PsiElement tableElement = dbElement(
                        project,
                        sourceHolder,
                        table,
                        definition.name());
                psiByObject.put(table, tableElement);
                psiByKey.put(tableKey, tableElement);
                for (DasColumn column : columns) {
                    PsiElement columnElement = dbElement(
                            project,
                            sourceHolder,
                            column,
                            column.getName());
                    psiByObject.put(column, columnElement);
                    psiByKey.put(tableKey + "." + column.getName(), columnElement);
                }
            }
            DasModel model = proxy(DasModel.class, (ignored, method, arguments) -> switch (
                    method.getName()) {
                case "traverser" -> JBTreeTraverser.<DasObject>from(value -> List.of())
                        .withRoots(tables);
                case "getCasing" -> Casing.EXACT;
                default -> defaultValue(method.getReturnType());
            });
            ModelNameIndex nameIndex = proxy(
                    ModelNameIndex.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getAllNames" -> psiByObject.keySet().stream()
                                .map(DasObject::getName)
                                .toList();
                        case "getObjectsByName" -> psiByObject.keySet().stream()
                                .filter(object -> object.getName().equals(arguments[0]))
                                .toList();
                        case "getObjectsByNameInsensitive" -> JBIterable.from(
                                psiByObject.keySet().stream()
                                        .filter(object -> object.getName()
                                                .equalsIgnoreCase((String) arguments[0]))
                                        .toList());
                        default -> defaultValue(method.getReturnType());
                    });
            DbDataSource dataSource = proxy(
                    DbDataSource.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getUniqueId" -> "main";
                        case "getName" -> "Main";
                        case "getDbms" -> Dbms.MYSQL;
                        case "getDatabaseDialect" ->
                                DatabaseDialects.findByDbms(Dbms.MYSQL);
                        case "getModel" -> model;
                        case "getNameIndex" -> nameIndex;
                        case "isLoading" -> loading;
                        case "getModificationTracker" ->
                                (ModificationTracker) modificationCount::get;
                        case "findElement" -> psiByObject.get(arguments[0]);
                        case "getDataSource" -> ignored;
                        case "getDasObject" -> ignored;
                        case "getKind" -> ObjectKind.ROOT;
                        case "getDasChildren" -> arguments[0] == ObjectKind.SCHEMA
                                || arguments[0] == ObjectKind.TABLE
                                ? JBIterable.from(tables)
                                : JBIterable.empty();
                        case "isValid" -> true;
                        case "getProject" -> project;
                        case "getManager" -> PsiManager.getInstance(project);
                        default -> defaultValue(method.getReturnType());
                    });
            sourceHolder[0] = dataSource;
            DbPsiFacade facade = new DbPsiFacade() {
                @Override
                public void clearCaches() {
                }

                @Override
                public com.intellij.openapi.project.Project getProject() {
                    return project;
                }

                @Override
                public DataSourceManager<RawDataSource> getDataSourceManager(
                        DbDataSource source) {
                    return null;
                }

                @Override
                public List<DbDataSource> getDataSources() {
                    return List.of(dataSource);
                }

                @Override
                public DbDataSource findDataSource(String id) {
                    return "main".equals(id) ? dataSource : null;
                }

                @Override
                @SuppressWarnings("removal")
                public DbElement findElement(DasObject object) {
                    return (DbElement) psiByObject.get(object);
                }
            };
            return new DatabaseFixture(
                    facade,
                    Map.copyOf(psiByKey),
                    modificationCount);
        }

        private static DasColumn column(String name, DasTable[] tableHolder) {
            DasBuiltinTypeClass<?> typeClass = proxy(
                    DasBuiltinTypeClass.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getCategory" -> DasTypeCategory.INTEGER;
                        case "getName" -> "BIGINT";
                        case "getCanonical" -> ignored;
                        default -> defaultValue(method.getReturnType());
                    });
            DasBuiltinType<?> type = proxy(
                    DasBuiltinType.class,
                    (ignored, method, arguments) -> switch (method.getName()) {
                        case "getTypeClass" -> typeClass;
                        case "getSpecification", "getDescription" -> "BIGINT";
                        case "withTypeClass" -> ignored;
                        default -> defaultValue(method.getReturnType());
                    });
            return proxy(DasColumn.class, (ignored, method, arguments) -> switch (
                    method.getName()) {
                case "getName" -> name;
                case "getKind" -> ObjectKind.COLUMN;
                case "getDasParent" -> tableHolder[0];
                case "getDasType" -> type;
                case "getPosition" -> (short) 1;
                case "isNotNull" -> true;
                case "isQuoted" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }

        private static DasObject object(
                String name,
                ObjectKind kind,
                DasObject parent) {
            return proxy(DasObject.class, (ignored, method, arguments) -> switch (
                    method.getName()) {
                case "getName" -> name;
                case "getKind" -> kind;
                case "getDasParent" -> parent;
                case "isQuoted" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }

        private static PsiElement dbElement(
                com.intellij.openapi.project.Project project,
                DbDataSource[] sourceHolder,
                DasObject object,
                String name) {
            return proxy(DbElement.class, (proxy, method, arguments) -> switch (
                    method.getName()) {
                case "getDelegate" -> object;
                case "getDasObject" -> object;
                case "getKind" -> object.getKind();
                case "getDbms" -> Dbms.MYSQL;
                case "getDasParent" -> object.getDasParent();
                case "getDasChildren" -> object.getDasChildren(
                        (ObjectKind) arguments[0]);
                case "getName", "getText" -> name;
                case "getProject" -> project;
                case "getLanguage" -> XMLLanguage.INSTANCE;
                case "getManager" -> PsiManager.getInstance(project);
                case "getDataSource" -> sourceHolder[0];
                case "getUseScope", "getResolveScope" ->
                        GlobalSearchScope.projectScope(project);
                case "getNavigationElement", "getOriginalElement" -> proxy;
                case "getTextRange" -> TextRange.EMPTY_RANGE;
                case "isValid", "canNavigate", "canNavigateToSource" -> true;
                case "isPhysical", "isWritable", "isDirectory" -> false;
                default -> defaultValue(method.getReturnType());
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(
                Class<T> type,
                java.lang.reflect.InvocationHandler handler) {
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
}
