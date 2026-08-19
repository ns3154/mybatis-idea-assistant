package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMessages;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolKind;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolOccurrence;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 数据库对象引用使用的稳定、无 PSI 目标键。
 */
record MyBatisDatabaseObjectTarget(
        @NotNull String dataSourceId,
        @NotNull String dataSourceDisplayName,
        long dataSourceModificationCount,
        @NotNull Optional<String> catalog,
        @NotNull Optional<String> schema,
        @NotNull String tableName,
        @NotNull Optional<String> columnName) {
    MyBatisDatabaseObjectTarget {
        if (dataSourceId.isBlank()
                || dataSourceDisplayName.isBlank()
                || dataSourceModificationCount < 0
                || tableName.isBlank()) {
            throw new IllegalArgumentException(MyBatisDatabaseMessages.message(
                    "database.error.object.target.invalid"));
        }
        catalog = catalog.filter(value -> !value.isBlank());
        schema = schema.filter(value -> !value.isBlank());
        columnName = columnName.filter(value -> !value.isBlank());
    }

    static @NotNull Optional<MyBatisDatabaseObjectTarget> fromOccurrence(
            @NotNull MyBatisSqlSymbolOccurrence occurrence,
            @NotNull List<MyBatisDatabaseSnapshot> snapshots) {
        if (occurrence.status() != MyBatisSqlSymbolStatus.RESOLVED
                || occurrence.candidates().size() != 1) {
            return Optional.empty();
        }
        String expected = occurrence.candidates().getFirst();
        List<MyBatisDatabaseObjectTarget> matches = new ArrayList<>();
        for (MyBatisDatabaseSnapshot snapshot : snapshots) {
            ProgressManager.checkCanceled();
            for (MyBatisDatabaseTable table : snapshot.tables()) {
                ProgressManager.checkCanceled();
                if (occurrence.kind() == MyBatisSqlSymbolKind.TABLE
                        && expected.equals(tableDisplayName(snapshot, table))) {
                    matches.add(target(snapshot, table, Optional.empty()));
                } else if (occurrence.kind() == MyBatisSqlSymbolKind.COLUMN) {
                    collectColumnTargets(expected, snapshot, table, matches);
                }
            }
        }
        return matches.size() == 1
                ? Optional.of(matches.getFirst())
                : Optional.empty();
    }

    private static void collectColumnTargets(
            @NotNull String expected,
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table,
            @NotNull List<MyBatisDatabaseObjectTarget> matches) {
        String tableName = tableDisplayName(snapshot, table);
        for (MyBatisDatabaseColumn column : table.columns()) {
            ProgressManager.checkCanceled();
            if (expected.equals(tableName + "." + column.name())) {
                matches.add(target(snapshot, table, Optional.of(column.name())));
            }
        }
    }

    private static @NotNull MyBatisDatabaseObjectTarget target(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table,
            @NotNull Optional<String> columnName) {
        return new MyBatisDatabaseObjectTarget(
                snapshot.dataSourceId(),
                snapshot.displayName(),
                snapshot.modificationCount(),
                table.catalog(),
                table.schema(),
                table.name(),
                columnName);
    }

    private static @NotNull String tableDisplayName(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table) {
        return snapshot.displayName()
                + ":"
                + table.schema().map(value -> value + ".").orElse("")
                + table.name();
    }
}
