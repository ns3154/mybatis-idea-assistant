package io.github.ns3154.mybatisassistant.database;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在统一后台、取消和超时边界内聚合所有元数据提供方。
 */
public final class MyBatisDatabaseMetadataService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private final Project project;
    private final AtomicReference<MyBatisDatabaseMetadataResult.Loaded> latest =
            new AtomicReference<>();
    private final AtomicReference<CompletableFuture<MyBatisDatabaseMetadataResult>> refreshInFlight =
            new AtomicReference<>();
    private final AtomicLong cacheGeneration = new AtomicLong();

    public MyBatisDatabaseMetadataService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull MyBatisDatabaseMetadataService getInstance(@NotNull Project project) {
        return project.getService(MyBatisDatabaseMetadataService.class);
    }

    public @NotNull CompletableFuture<MyBatisDatabaseMetadataResult> load(
            @NotNull MyBatisDatabaseRequest request) {
        return load(request, DEFAULT_TIMEOUT);
    }

    public @NotNull Optional<MyBatisDatabaseMetadataResult.Loaded> latest() {
        return Optional.ofNullable(latest.get());
    }

    /**
     * 丢弃已完成快照并取消统一刷新；失效前启动的后台结果不得重新写回缓存。
     */
    public synchronized void invalidate() {
        cacheGeneration.incrementAndGet();
        latest.set(null);
        CompletableFuture<MyBatisDatabaseMetadataResult> inFlight =
                refreshInFlight.getAndSet(null);
        if (inFlight != null && !inFlight.isDone()) {
            inFlight.cancel(false);
        }
    }

    public synchronized @NotNull CompletableFuture<MyBatisDatabaseMetadataResult> refresh() {
        CompletableFuture<MyBatisDatabaseMetadataResult> existing = refreshInFlight.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<MyBatisDatabaseMetadataResult> created = load(
                MyBatisDatabaseRequest.all(),
                DEFAULT_TIMEOUT);
        refreshInFlight.set(created);
        created.whenComplete((ignored, failure) ->
                refreshInFlight.compareAndSet(created, null));
        return created;
    }

    public @NotNull CompletableFuture<MyBatisDatabaseMetadataResult> load(
            @NotNull MyBatisDatabaseRequest request,
            @NotNull Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("元数据超时必须大于零");
        }
        if (project.isDisposed() || !project.isOpen()) {
            return CompletableFuture.completedFuture(new MyBatisDatabaseMetadataResult.Unavailable(
                    MyBatisDatabaseMetadataResult.Reason.PROJECT_CLOSED));
        }

        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        CompletableFuture<MyBatisDatabaseMetadataResult> result = new CompletableFuture<>();
        long generation = cacheGeneration.get();
        Future<?> worker = AppExecutorUtil.getAppExecutorService().submit(() ->
                loadInBackground(request, indicator, result, generation));
        ScheduledFuture<?> timeoutTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                () -> {
                    if (result.complete(new MyBatisDatabaseMetadataResult.Unavailable(
                            MyBatisDatabaseMetadataResult.Reason.TIMED_OUT))) {
                        indicator.cancel();
                        worker.cancel(true);
                    }
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, failure) -> {
            timeoutTask.cancel(false);
            if (result.isCancelled()) {
                indicator.cancel();
                worker.cancel(true);
            }
        });
        return result;
    }

    private void loadInBackground(
            @NotNull MyBatisDatabaseRequest request,
            @NotNull EmptyProgressIndicator indicator,
            @NotNull CompletableFuture<MyBatisDatabaseMetadataResult> result,
            long generation) {
        try {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                throw new IllegalStateException("元数据提供方不得在 EDT 调用");
            }
            List<MyBatisDatabaseMetadataProvider> providers =
                    MyBatisDatabaseMetadataProvider.EP_NAME.getExtensionList();
            if (providers.isEmpty()) {
                result.complete(new MyBatisDatabaseMetadataResult.Unavailable(
                        MyBatisDatabaseMetadataResult.Reason.NO_PROVIDER));
                return;
            }
            List<MyBatisDatabaseSnapshot> snapshots = new ArrayList<>();
            List<String> failedProviders = new ArrayList<>();
            for (MyBatisDatabaseMetadataProvider provider : providers) {
                indicator.checkCanceled();
                if (project.isDisposed() || !project.isOpen()) {
                    result.complete(new MyBatisDatabaseMetadataResult.Unavailable(
                            MyBatisDatabaseMetadataResult.Reason.PROJECT_CLOSED));
                    return;
                }
                try {
                    snapshots.addAll(provider.load(project, request, indicator));
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException failure) {
                    failedProviders.add(provider.id());
                }
            }
            indicator.checkCanceled();
            List<MyBatisDatabaseSnapshot> filtered = filterAndSort(snapshots, request);
            if (!filtered.isEmpty()) {
                MyBatisDatabaseMetadataResult.Loaded loaded =
                        new MyBatisDatabaseMetadataResult.Loaded(filtered);
                if (result.complete(loaded)) {
                    updateLatestIfCurrent(generation, loaded);
                }
            } else if (!failedProviders.isEmpty()) {
                MyBatisDatabaseMetadataResult.Failed failed =
                        new MyBatisDatabaseMetadataResult.Failed(failedProviders);
                if (result.complete(failed)) {
                    updateLatestIfCurrent(generation, null);
                }
            } else {
                MyBatisDatabaseMetadataResult.Unavailable unavailable =
                        new MyBatisDatabaseMetadataResult.Unavailable(
                                MyBatisDatabaseMetadataResult.Reason.NO_DATA_SOURCE);
                if (result.complete(unavailable)) {
                    updateLatestIfCurrent(generation, null);
                }
            }
        } catch (CancellationException cancelled) {
            result.cancel(false);
        } catch (RuntimeException failure) {
            result.complete(new MyBatisDatabaseMetadataResult.Failed(
                    List.of("metadata-service")));
        }
    }

    private synchronized void updateLatestIfCurrent(
            long generation,
            MyBatisDatabaseMetadataResult.Loaded loaded) {
        if (cacheGeneration.get() == generation) {
            latest.set(loaded);
        }
    }

    private static @NotNull List<MyBatisDatabaseSnapshot> filterAndSort(
            @NotNull List<MyBatisDatabaseSnapshot> snapshots,
            @NotNull MyBatisDatabaseRequest request) {
        List<MyBatisDatabaseSnapshot> filtered = new ArrayList<>();
        for (MyBatisDatabaseSnapshot snapshot : snapshots) {
            if (request.dataSourceId().isPresent()
                    && !request.dataSourceId().orElseThrow().equals(snapshot.dataSourceId())) {
                continue;
            }
            List<MyBatisDatabaseTable> tables = request.schemaName().isEmpty()
                    ? snapshot.tables()
                    : snapshot.tables().stream()
                            .filter(table -> table.schema().map(request.schemaName().orElseThrow()::equals)
                                    .orElse(false))
                            .toList();
            filtered.add(new MyBatisDatabaseSnapshot(
                    snapshot.dataSourceId(),
                    snapshot.displayName(),
                    snapshot.dialect(),
                    snapshot.freshness(),
                    snapshot.modificationCount(),
                    tables));
        }
        filtered.sort(Comparator.comparing(MyBatisDatabaseSnapshot::displayName)
                .thenComparing(MyBatisDatabaseSnapshot::dataSourceId));
        return List.copyOf(filtered);
    }
}
