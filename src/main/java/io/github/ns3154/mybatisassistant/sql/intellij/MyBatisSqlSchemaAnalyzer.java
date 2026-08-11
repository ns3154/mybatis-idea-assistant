package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.database.model.ObjectKind;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.sql.psi.SqlAsExpression;
import com.intellij.sql.psi.SqlExpression;
import com.intellij.sql.psi.SqlReferenceExpression;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 使用公开 SQL PSI 角色和完整快照解析表、列与别名。
 */
public final class MyBatisSqlSchemaAnalyzer {
    private static final String DYNAMIC_IDENTIFIER = "__mybatis_dynamic__";

    private MyBatisSqlSchemaAnalyzer() {
    }

    public static @NotNull MyBatisSqlSchemaAnalysis analyze(
            @NotNull MyBatisSqlPsiResult.Ready sql,
            @NotNull List<MyBatisDatabaseSnapshot> snapshots) {
        ProgressManager.checkCanceled();
        if (snapshots.isEmpty() || snapshots.stream().anyMatch(
                snapshot -> snapshot.freshness() != MyBatisMetadataFreshness.READY)) {
            return new MyBatisSqlSchemaAnalysis(List.of(), false);
        }
        List<SqlReferenceExpression> references = new ArrayList<>(
                PsiTreeUtil.findChildrenOfType(
                        sql.psiFile(),
                        SqlReferenceExpression.class));
        List<MyBatisSqlSymbolOccurrence> occurrences = new ArrayList<>();
        Map<String, List<TableCandidate>> bindings = new HashMap<>();
        boolean tablesComplete = analyzeTables(
                references,
                snapshots,
                occurrences,
                bindings);
        analyzeColumns(references, occurrences, bindings, tablesComplete);
        return new MyBatisSqlSchemaAnalysis(occurrences, true);
    }

    private static boolean analyzeTables(
            @NotNull List<SqlReferenceExpression> references,
            @NotNull List<MyBatisDatabaseSnapshot> snapshots,
            @NotNull List<MyBatisSqlSymbolOccurrence> occurrences,
            @NotNull Map<String, List<TableCandidate>> bindings) {
        boolean complete = true;
        for (SqlReferenceExpression reference : references) {
            ProgressManager.checkCanceled();
            if (!ObjectKind.TABLE.equals(reference.getReferenceElementType().getTargetKind())) {
                continue;
            }
            String name = reference.getName();
            if (name == null || name.isBlank() || reference.getIdentifier() == null
                    || DYNAMIC_IDENTIFIER.equals(name)) {
                complete = false;
                continue;
            }
            String schema = blankToNull(reference.getReferencePart(ObjectKind.SCHEMA));
            List<TableCandidate> candidates = findTables(snapshots, schema, name);
            MyBatisSqlSymbolStatus status = status(candidates.size());
            if (status != MyBatisSqlSymbolStatus.RESOLVED) {
                complete = false;
            }
            occurrences.add(occurrence(
                    MyBatisSqlSymbolKind.TABLE,
                    status,
                    name,
                    Optional.ofNullable(schema),
                    reference,
                    candidateNames(candidates)));
            if (candidates.size() == 1) {
                addBinding(bindings, name, candidates.getFirst());
                String alias = alias(reference);
                if (alias != null) {
                    addBinding(bindings, alias, candidates.getFirst());
                }
            }
        }
        return complete;
    }

    private static void analyzeColumns(
            @NotNull List<SqlReferenceExpression> references,
            @NotNull List<MyBatisSqlSymbolOccurrence> occurrences,
            @NotNull Map<String, List<TableCandidate>> bindings,
            boolean tablesComplete) {
        for (SqlReferenceExpression reference : references) {
            ProgressManager.checkCanceled();
            if (!ObjectKind.COLUMN.equals(reference.getReferenceElementType().getTargetKind())) {
                continue;
            }
            String name = reference.getName();
            if (name == null || name.isBlank() || reference.getIdentifier() == null
                    || DYNAMIC_IDENTIFIER.equals(name)) {
                continue;
            }
            String qualifier = qualifier(reference.getQualifierExpression());
            List<TableCandidate> tables = qualifier == null
                    ? uniqueTables(bindings)
                    : bindings.getOrDefault(normalize(qualifier), List.of());
            MyBatisSqlSymbolStatus status;
            List<String> candidates;
            if (!tablesComplete || tables.isEmpty()) {
                status = MyBatisSqlSymbolStatus.UNKNOWN;
                candidates = List.of();
            } else {
                List<ColumnCandidate> columns = findColumns(tables, name);
                status = status(columns.size());
                candidates = columnCandidateNames(columns);
            }
            occurrences.add(occurrence(
                    MyBatisSqlSymbolKind.COLUMN,
                    status,
                    name,
                    Optional.ofNullable(qualifier),
                    reference,
                    candidates));
        }
    }

    private static @NotNull List<TableCandidate> findTables(
            @NotNull List<MyBatisDatabaseSnapshot> snapshots,
            @Nullable String schema,
            @NotNull String name) {
        List<TableCandidate> result = new ArrayList<>();
        for (MyBatisDatabaseSnapshot snapshot : snapshots) {
            ProgressManager.checkCanceled();
            for (MyBatisDatabaseTable table : snapshot.tables()) {
                ProgressManager.checkCanceled();
                if (equalsName(table.name(), name)
                        && (schema == null || table.schema().map(value -> equalsName(value, schema))
                                .orElse(false))) {
                    result.add(new TableCandidate(snapshot, table));
                }
            }
        }
        return List.copyOf(result);
    }

    private static @NotNull List<ColumnCandidate> findColumns(
            @NotNull List<TableCandidate> tables,
            @NotNull String name) {
        List<ColumnCandidate> result = new ArrayList<>();
        for (TableCandidate table : tables) {
            ProgressManager.checkCanceled();
            for (MyBatisDatabaseColumn column : table.table.columns()) {
                ProgressManager.checkCanceled();
                if (equalsName(column.name(), name)) {
                    result.add(new ColumnCandidate(table, column));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addBinding(
            @NotNull Map<String, List<TableCandidate>> bindings,
            @NotNull String name,
            @NotNull TableCandidate candidate) {
        bindings.computeIfAbsent(normalize(name), ignored -> new ArrayList<>()).add(candidate);
    }

    private static @NotNull List<TableCandidate> uniqueTables(
            @NotNull Map<String, List<TableCandidate>> bindings) {
        Set<TableCandidate> unique = new LinkedHashSet<>();
        for (List<TableCandidate> candidates : bindings.values()) {
            ProgressManager.checkCanceled();
            unique.addAll(candidates);
        }
        return List.copyOf(unique);
    }

    private static @Nullable String alias(@NotNull SqlReferenceExpression reference) {
        if (reference.getParent() instanceof SqlAsExpression asExpression
                && asExpression.getExpression() == reference
                && asExpression.getNameElement() != null) {
            return asExpression.getNameElement().getName();
        }
        return null;
    }

    private static @Nullable String qualifier(@Nullable SqlExpression qualifier) {
        if (qualifier instanceof SqlReferenceExpression reference) {
            return reference.getName();
        }
        return qualifier == null ? null : qualifier.getText();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static @NotNull MyBatisSqlSymbolOccurrence occurrence(
            @NotNull MyBatisSqlSymbolKind kind,
            @NotNull MyBatisSqlSymbolStatus status,
            @NotNull String name,
            @NotNull Optional<String> qualifier,
            @NotNull SqlReferenceExpression reference,
            @NotNull List<String> candidates) {
        var range = reference.getIdentifier().getTextRange();
        return new MyBatisSqlSymbolOccurrence(
                kind,
                status,
                name,
                qualifier,
                new MyBatisTextRange(range.getStartOffset(), range.getEndOffset()),
                candidates);
    }

    private static @NotNull MyBatisSqlSymbolStatus status(int count) {
        if (count == 0) {
            return MyBatisSqlSymbolStatus.MISSING;
        }
        return count == 1
                ? MyBatisSqlSymbolStatus.RESOLVED
                : MyBatisSqlSymbolStatus.AMBIGUOUS;
    }

    private static @NotNull List<String> candidateNames(
            @NotNull List<TableCandidate> candidates) {
        return candidates.stream().map(TableCandidate::displayName).toList();
    }

    private static @NotNull List<String> columnCandidateNames(
            @NotNull List<ColumnCandidate> candidates) {
        return candidates.stream().map(ColumnCandidate::displayName).toList();
    }

    private static boolean equalsName(@NotNull String first, @NotNull String second) {
        return first.equalsIgnoreCase(second);
    }

    private static @NotNull String normalize(@NotNull String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record TableCandidate(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table) {
        private @NotNull String displayName() {
            return snapshot.displayName()
                    + ":"
                    + table.schema().map(value -> value + ".").orElse("")
                    + table.name();
        }
    }

    private record ColumnCandidate(
            @NotNull TableCandidate table,
            @NotNull MyBatisDatabaseColumn column) {
        private @NotNull String displayName() {
            return table.displayName() + "." + column.name();
        }
    }
}
