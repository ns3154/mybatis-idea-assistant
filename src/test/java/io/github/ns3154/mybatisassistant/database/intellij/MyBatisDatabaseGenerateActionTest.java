package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.Dbms;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbElement;
import com.intellij.database.psi.DbTable;
import com.intellij.database.types.DasBuiltinType;
import com.intellij.database.types.DasBuiltinTypeClass;
import com.intellij.database.types.DasTypeCategory;
import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.containers.JBIterable;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationEngine;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationPlan;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationRequest;
import io.github.ns3154.mybatisassistant.testutil.MyBatisPerformanceProgressIndicator;

import java.lang.reflect.Proxy;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MyBatisDatabaseGenerateActionTest extends BasePlatformTestCase {
    public void testOptionalDescriptorRegistersActionInDatabasePopup() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisDatabaseGenerateAction.ID);

        assertInstanceOf(action, MyBatisDatabaseGenerateAction.class);
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
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

    public void testSingleTablePreviewP95IncludesLoadedMetadataAndRunsOffEdt()
            throws Exception {
        int sampleCount = 30;
        VirtualFile root = myFixture.getTempDirFixture()
                .findOrCreateDir("single-table-preview-performance");
        MyBatisGenerationConfiguration configuration =
                MyBatisGenerationConfiguration.standard("com.example");
        AtomicBoolean providerWithoutReadAccess = new AtomicBoolean();
        AtomicInteger providerReads = new AtomicInteger();

        Future<GenerationBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    long[] durations = new long[sampleCount];
                    int[] entryCounts = new int[sampleCount];
                    boolean dispatchThread = ApplicationManager.getApplication()
                            .isDispatchThread();
                    for (int sample = 0; sample < sampleCount; sample++) {
                        MyBatisPerformanceProgressIndicator indicator =
                                new MyBatisPerformanceProgressIndicator();
                        String tableName = "single_preview_" + sample;
                        DbTable table = loadedTable(
                                tableName,
                                providerWithoutReadAccess,
                                providerReads);
                        long started = System.nanoTime();
                        MyBatisGenerationPlan plan = ProgressManager.getInstance().runProcess(
                                () -> MyBatisDatabaseGenerateAction.buildPlan(
                                        getProject(),
                                        root,
                                        new DbTable[]{table},
                                        configuration,
                                        indicator),
                                indicator);
                        durations[sample] = System.nanoTime() - started;
                        entryCounts[sample] = plan.entries().size();
                    }
                    return new GenerationBenchmark(
                            dispatchThread,
                            durations,
                            entryCounts);
                });

        GenerationBenchmark benchmark = future.get(60, TimeUnit.SECONDS);
        assertFalse("单表生成预览不得运行在 EDT", benchmark.dispatchThread());
        assertFalse("已加载 Database Tools 模型必须在短读动作中提取",
                providerWithoutReadAccess.get());
        assertEquals("每个样本只读取一次已加载表列快照", sampleCount, providerReads.get());
        assertTrue(Arrays.stream(benchmark.entryCounts()).allMatch(count -> count == 4));
        assertP95Below("单表生成预览", benchmark.durations(), 3_000.0);
    }

    public void testHundredTablePreviewReportsMonotonicProgressOffEdt()
            throws Exception {
        int tableCount = 100;
        DbTable[] tables = tables(tableCount, "batch_preview_");
        VirtualFile root = myFixture.getTempDirFixture()
                .findOrCreateDir("hundred-table-preview-performance");
        MyBatisGenerationConfiguration configuration =
                MyBatisGenerationConfiguration.standard("com.example");

        Future<BatchBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    MyBatisPerformanceProgressIndicator indicator =
                            new MyBatisPerformanceProgressIndicator();
                    AtomicInteger snapshotQueries = new AtomicInteger();
                    AtomicInteger generationQueries = new AtomicInteger();
                    AtomicBoolean snapshotWithoutReadAccess = new AtomicBoolean();
                    AtomicBoolean generationOnEdt = new AtomicBoolean();
                    AtomicBoolean generationWithReadAccess = new AtomicBoolean();
                    List<FactoryProgressObservation> factoryProgress = new ArrayList<>();
                    boolean dispatchThread = ApplicationManager.getApplication()
                            .isDispatchThread();
                    MyBatisGenerationPlan plan = ProgressManager.getInstance().runProcess(
                            () -> MyBatisDatabaseGenerateAction.buildPlan(
                                            getProject(),
                                            root,
                                            tables,
                                            configuration,
                                            indicator,
                                            (table, progress) -> {
                                                snapshotQueries.incrementAndGet();
                                                if (!ApplicationManager.getApplication()
                                                        .isReadAccessAllowed()) {
                                                    snapshotWithoutReadAccess.set(true);
                                                }
                                                return snapshot(table.getName());
                                            },
                                            (snapshot, selectedConfiguration, progress) -> {
                                                generationQueries.incrementAndGet();
                                                factoryProgress.add(new FactoryProgressObservation(
                                                        snapshot.tableName(),
                                                        progress.getText2(),
                                                        progress.getFraction(),
                                                        ApplicationManager.getApplication()
                                                                .isReadAccessAllowed()));
                                                if (ApplicationManager.getApplication()
                                                        .isDispatchThread()) {
                                                    generationOnEdt.set(true);
                                                }
                                                if (ApplicationManager.getApplication()
                                                        .isReadAccessAllowed()) {
                                                    generationWithReadAccess.set(true);
                                                }
                                                return generateBundle(
                                                        snapshot.tableName(),
                                                        selectedConfiguration);
                                            }),
                            indicator);
                    return new BatchBenchmark(
                            dispatchThread,
                            snapshotWithoutReadAccess.get(),
                            generationOnEdt.get(),
                            generationWithReadAccess.get(),
                            snapshotQueries.get(),
                            generationQueries.get(),
                            plan.entries().size(),
                            indicator.checkCount(),
                            indicator.fractions(),
                            indicator.secondaryTexts(),
                            indicator.secondaryTextReadAccess(),
                            indicator.fractionReadAccess(),
                            indicator.cancellationPolls(),
                            List.copyOf(factoryProgress));
                });

        BatchBenchmark benchmark = future.get(120, TimeUnit.SECONDS);
        assertFalse("百表生成编排不得运行在 EDT", benchmark.dispatchThread());
        assertFalse("每张表的元数据快照必须处于局部读动作",
                benchmark.snapshotWithoutReadAccess());
        assertFalse("百表 CPU 生成不得运行在 EDT", benchmark.generationOnEdt());
        assertFalse("百表 CPU 生成不得持有 PSI 读锁",
                benchmark.generationWithReadAccess());
        assertEquals("每张表只允许提取一次元数据快照",
                tableCount, benchmark.snapshotQueries());
        assertEquals("每张表只允许执行一次 CPU 生成",
                tableCount, benchmark.generationQueries());
        assertEquals("100 张表每表应形成四个候选文件", tableCount * 4,
                benchmark.entryCount());
        assertTrue("表循环和逐文件计划都必须有取消点，实际=" + benchmark.checkCount(),
                benchmark.checkCount() >= tableCount * 5);
        assertMonotonicProgress(benchmark.fractions());
        assertTrue("进度必须覆盖逐表生成阶段",
                benchmark.fractions().stream().anyMatch(value -> value > 0.0 && value < 0.5));
        assertTrue("进度必须覆盖逐文件计划阶段",
                benchmark.fractions().stream().anyMatch(value -> value > 0.5 && value < 1.0));
        assertEquals("每张表都必须展示当前进度文本", tableCount,
                benchmark.secondaryTexts().stream().filter(text -> !text.isEmpty()).count());
        assertFalse("当前表进度文案必须在短读动作之外更新",
                benchmark.secondaryTextReadAccess().stream().anyMatch(Boolean::booleanValue));
        assertFalse("表间与阶段进度更新时不得持有覆盖整批的读锁",
                benchmark.fractionReadAccess().stream().anyMatch(Boolean::booleanValue));
        assertEquals(tableCount, benchmark.factoryProgress().size());
        for (int index = 0; index < tableCount; index++) {
            FactoryProgressObservation observation = benchmark.factoryProgress().get(index);
            String expectedTable = "batch_preview_" + index;
            assertEquals("CPU 生成必须对应当前快照", expectedTable, observation.tableName());
            assertEquals("慢 CPU 生成开始前必须先展示当前表", expectedTable,
                    observation.progressText());
            assertEquals("CPU 生成调用时 fraction 必须停在当前表起点",
                    0.5 * index / tableCount, observation.fraction(), 0.0);
            assertFalse("CPU 生成必须在逐表短读动作结束后执行",
                    observation.readAccess());
        }
        assertTrue("Planner 条目读取必须处于逐文件短读动作",
                benchmark.cancellationPolls().stream().anyMatch(poll ->
                        poll.fraction() >= 0.5 && poll.readAccess()));
        assertTrue("Planner 条目之间必须释放读锁",
                benchmark.cancellationPolls().stream().anyMatch(poll ->
                        poll.fraction() >= 0.5 && !poll.readAccess()));
    }

    public void testSlowCpuGenerationReleasesReadLockForConcurrentWriteAction()
            throws Exception {
        VirtualFile root = myFixture.getTempDirFixture()
                .findOrCreateDir("slow-generation-write-access");
        MyBatisGenerationConfiguration configuration =
                MyBatisGenerationConfiguration.standard("com.example");
        MyBatisPerformanceProgressIndicator indicator =
                new MyBatisPerformanceProgressIndicator();
        CountDownLatch generationStarted = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        CountDownLatch writeAcquired = new CountDownLatch(1);
        AtomicBoolean snapshotWithoutReadAccess = new AtomicBoolean();
        AtomicBoolean generationWithReadAccess = new AtomicBoolean();

        Future<MyBatisGenerationPlan> generation = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> MyBatisDatabaseGenerateAction.buildPlan(
                                getProject(),
                                root,
                                new DbTable[]{table("slow_table", true, false)},
                                configuration,
                                indicator,
                                (table, progress) -> {
                                    if (!ApplicationManager.getApplication()
                                            .isReadAccessAllowed()) {
                                        snapshotWithoutReadAccess.set(true);
                                    }
                                    return snapshot(table.getName());
                                },
                                (snapshot, selectedConfiguration, progress) -> {
                                    if (ApplicationManager.getApplication()
                                            .isReadAccessAllowed()) {
                                        generationWithReadAccess.set(true);
                                    }
                                    generationStarted.countDown();
                                    awaitLatch(releaseGeneration, "释放慢 CPU 生成");
                                    return generateBundle(
                                            snapshot.tableName(), selectedConfiguration);
                                }),
                        indicator));

        assertTrue("慢 CPU 生成必须实际开始",
                generationStarted.await(30, TimeUnit.SECONDS));
        AtomicBoolean acquiredBeforeRelease = new AtomicBoolean();
        Future<?> releaseCoordinator = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    try {
                        acquiredBeforeRelease.set(writeAcquired.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        releaseGeneration.countDown();
                    }
                });
        ApplicationManager.getApplication().runWriteAction(writeAcquired::countDown);
        releaseCoordinator.get(35, TimeUnit.SECONDS);

        try {
            assertTrue("慢 CPU 生成等待期间，写动作必须能先取得写锁",
                    acquiredBeforeRelease.get());
        } finally {
            releaseGeneration.countDown();
        }
        assertEquals(4, generation.get(30, TimeUnit.SECONDS).entries().size());
        assertFalse("元数据快照阶段必须持有局部读锁", snapshotWithoutReadAccess.get());
        assertFalse("慢 CPU 生成阶段不得持有读锁", generationWithReadAccess.get());
    }

    public void testHundredTablePreviewStopsAtCanceledTableWithoutEnteringPlanner()
            throws Exception {
        DbTable[] tables = tables(100, "cancel_preview_");
        VirtualFile root = myFixture.getTempDirFixture()
                .findOrCreateDir("canceled-table-preview-performance");
        MyBatisGenerationConfiguration configuration =
                MyBatisGenerationConfiguration.standard("com.example");

        Future<BatchCancellationBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    MyBatisPerformanceProgressIndicator indicator =
                            new MyBatisPerformanceProgressIndicator();
                    AtomicInteger processedTables = new AtomicInteger();
                    try {
                        ProgressManager.getInstance().runProcess(
                                () -> MyBatisDatabaseGenerateAction.buildPlan(
                                                getProject(),
                                                root,
                                                tables,
                                                configuration,
                                                indicator,
                                                (table, progress) -> snapshot(table.getName()),
                                                (snapshot, selectedConfiguration, progress) -> {
                                                    MyBatisGenerationBundle bundle =
                                                            generateBundle(
                                                                    snapshot.tableName(),
                                                                    selectedConfiguration);
                                                    if (processedTables.incrementAndGet() == 25) {
                                                        progress.cancel();
                                                    }
                                                    return bundle;
                                                }),
                                indicator);
                        return new BatchCancellationBenchmark(
                                false,
                                processedTables.get(),
                                indicator.checkCount(),
                                indicator.fractions());
                    } catch (ProcessCanceledException expected) {
                        return new BatchCancellationBenchmark(
                                true,
                                processedTables.get(),
                                indicator.checkCount(),
                                indicator.fractions());
                    }
                });

        BatchCancellationBenchmark benchmark = future.get(60, TimeUnit.SECONDS);
        assertTrue("百表生成必须传播中途取消", benchmark.canceled());
        assertEquals("取消后不得继续查询后续表", 25, benchmark.processedTables());
        assertTrue("每表边界必须检查取消", benchmark.checkCount() >= 25);
        assertTrue("取消后不得进入 0.5-1.0 的 planner 阶段",
                benchmark.fractions().stream().allMatch(value -> value <= 0.5));
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
        return table("S8 表", valid, loading);
    }

    private static DbTable table(String name, boolean valid, boolean loading) {
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
            case "getName" -> name;
            case "toString" -> "S8 表";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DbTable[] tables(int count, String prefix) {
        DbTable[] tables = new DbTable[count];
        for (int index = 0; index < count; index++) {
            tables[index] = table(prefix + index, true, false);
        }
        return tables;
    }

    private static DbTable loadedTable(
            String tableName,
            AtomicBoolean providerWithoutReadAccess,
            AtomicInteger providerReads) {
        DasObject schema = proxy(DasObject.class, (method, arguments) -> switch (
                method.getName()) {
            case "getName" -> "public";
            case "getKind" -> ObjectKind.SCHEMA;
            case "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
        DasTable[] tableHolder = new DasTable[1];
        DasColumn id = column(
                "id", 1, schema, tableHolder,
                DasTypeCategory.INTEGER, "BIGINT", true);
        DasColumn displayName = column(
                "display_name", 2, schema, tableHolder,
                DasTypeCategory.STRING, "VARCHAR", false);
        DasTable dasTable = proxy(DasTable.class, (method, arguments) -> switch (
                method.getName()) {
            case "getName" -> tableName;
            case "getKind" -> ObjectKind.TABLE;
            case "getDasParent" -> schema;
            case "getDasChildren" -> {
                if (!ApplicationManager.getApplication().isReadAccessAllowed()) {
                    providerWithoutReadAccess.set(true);
                }
                if (arguments[0] == ObjectKind.COLUMN) {
                    providerReads.incrementAndGet();
                }
                yield arguments[0] == ObjectKind.COLUMN
                        ? JBIterable.of(id, displayName)
                        : JBIterable.empty();
            }
            case "getColumnAttrs" -> arguments[0] == id
                    ? java.util.Set.of(DasColumn.Attribute.PRIMARY_KEY)
                    : java.util.Set.of();
            case "isSystem", "isTemporary", "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
        tableHolder[0] = dasTable;
        DbDataSource source = proxy(DbDataSource.class, (method, arguments) -> switch (
                method.getName()) {
            case "getUniqueId" -> "performance";
            case "getName" -> "Performance";
            case "getDbms" -> Dbms.POSTGRES;
            case "isLoading" -> false;
            case "isValid" -> true;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(DbTable.class, (method, arguments) -> switch (method.getName()) {
            case "isValid" -> true;
            case "getDataSource" -> source;
            case "getDasObject" -> dasTable;
            case "getName" -> tableName;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static DasColumn column(
            String name,
            int position,
            DasObject parent,
            DasTable[] tableHolder,
            DasTypeCategory category,
            String specification,
            boolean notNull) {
        DasBuiltinTypeClass<?>[] typeClassHolder = new DasBuiltinTypeClass<?>[1];
        DasBuiltinTypeClass<?> typeClass = proxy(
                DasBuiltinTypeClass.class,
                (method, arguments) -> switch (method.getName()) {
                    case "getCategory" -> category;
                    case "getName" -> specification;
                    case "getCanonical" -> typeClassHolder[0];
                    default -> defaultValue(method.getReturnType());
                });
        typeClassHolder[0] = typeClass;
        DasBuiltinType<?>[] typeHolder = new DasBuiltinType<?>[1];
        DasBuiltinType<?> type = proxy(
                DasBuiltinType.class,
                (method, arguments) -> switch (method.getName()) {
                    case "getTypeClass" -> typeClass;
                    case "getSpecification", "getDescription" -> specification;
                    case "withTypeClass" -> typeHolder[0];
                    default -> defaultValue(method.getReturnType());
                });
        typeHolder[0] = type;
        return proxy(DasColumn.class, (method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getKind" -> ObjectKind.COLUMN;
            case "getDasParent" -> parent;
            case "getTable" -> tableHolder[0];
            case "getDasType" -> type;
            case "getPosition" -> (short) position;
            case "isNotNull" -> notNull;
            case "isQuoted" -> false;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static MyBatisGenerationBundle generateBundle(
            String tableName,
            MyBatisGenerationConfiguration configuration) {
        return MyBatisGenerationEngine.generate(new MyBatisGenerationRequest(
                "performance",
                MyBatisSqlDialect.POSTGRESQL,
                databaseTable(tableName),
                configuration));
    }

    private static MyBatisDatabaseGenerateAction.DatabaseGenerationSnapshot snapshot(
            String tableName) {
        return new MyBatisDatabaseGenerateAction.DatabaseGenerationSnapshot(
                tableName,
                "performance",
                MyBatisSqlDialect.POSTGRESQL,
                databaseTable(tableName));
    }

    private static MyBatisDatabaseTable databaseTable(String tableName) {
        return new MyBatisDatabaseTable(
                Optional.empty(),
                Optional.of("public"),
                tableName,
                Optional.of("性能预算表"),
                List.of(
                        new MyBatisDatabaseColumn(
                                "id", "BIGINT", Types.BIGINT,
                                false, true, false, true,
                                Optional.of("主键"), 1),
                        new MyBatisDatabaseColumn(
                                "display_name", "VARCHAR", Types.VARCHAR,
                                true, false, false, false,
                                Optional.of("名称"), 2)));
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("等待超时：" + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断：" + description, interrupted);
        }
    }

    private void assertP95Below(String path, long[] durations, double thresholdMillis) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        int percentileIndex = (int) Math.ceil(sorted.length * 0.95) - 1;
        double p95Millis = sorted[percentileIndex] / 1_000_000.0;
        System.out.println(path + " P95=" + p95Millis + "ms，样本数=" + sorted.length);
        assertTrue(
                path + " P95 为 " + p95Millis + "ms，阈值为 " + thresholdMillis + "ms",
                p95Millis < thresholdMillis);
    }

    private void assertMonotonicProgress(List<Double> fractions) {
        assertFalse("进度序列不能为空", fractions.isEmpty());
        assertEquals(0.0, fractions.getFirst(), 0.0);
        assertEquals(1.0, fractions.getLast(), 0.0);
        for (int index = 1; index < fractions.size(); index++) {
            assertTrue("进度不得倒退：" + fractions,
                    fractions.get(index) >= fractions.get(index - 1));
        }
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
                    return invocation.invoke(method, arguments);
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
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return 0;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }

    private record GenerationBenchmark(
            boolean dispatchThread,
            long[] durations,
            int[] entryCounts) {
    }

    private record BatchBenchmark(
            boolean dispatchThread,
            boolean snapshotWithoutReadAccess,
            boolean generationOnEdt,
            boolean generationWithReadAccess,
            int snapshotQueries,
            int generationQueries,
            int entryCount,
            int checkCount,
            List<Double> fractions,
            List<String> secondaryTexts,
            List<Boolean> secondaryTextReadAccess,
            List<Boolean> fractionReadAccess,
            List<MyBatisPerformanceProgressIndicator.CancellationPoll> cancellationPolls,
            List<FactoryProgressObservation> factoryProgress) {
    }

    private record FactoryProgressObservation(
            String tableName,
            String progressText,
            double fraction,
            boolean readAccess) {
    }

    private record BatchCancellationBenchmark(
            boolean canceled,
            int processedTables,
            int checkCount,
            List<Double> fractions) {
    }
}
