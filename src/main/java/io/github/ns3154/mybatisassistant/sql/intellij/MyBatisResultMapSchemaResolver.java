package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlDiagnosticCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把引用 ResultMap 的静态 statement 收敛到一个已加载的物理表。
 */
public final class MyBatisResultMapSchemaResolver {
    private MyBatisResultMapSchemaResolver() {
    }

    public static @NotNull Optional<ResolvedTable> resolve(
            @NotNull XmlTag resultMap,
            @NotNull List<MyBatisDatabaseSnapshot> snapshots) {
        ProgressManager.checkCanceled();
        if (!resultMap.isValid()
                || resultMap.getProject().isDisposed()
                || !resultMap.getProject().isOpen()
                || DumbService.isDumb(resultMap.getProject())
                || snapshots.isEmpty()
                || snapshots.stream().anyMatch(snapshot ->
                        snapshot.freshness() != MyBatisMetadataFreshness.READY)) {
            return Optional.empty();
        }
        XmlTag mapper = resultMap.getParentTag();
        String resultMapId = staticValue(resultMap.getAttributeValue("id"));
        String namespace = mapper == null
                ? null
                : staticValue(MyBatisXmlModel.namespace(mapper));
        if (mapper == null
                || !MyBatisXmlModel.isMapperRoot(mapper)
                || resultMapId == null
                || namespace == null
                || !isUniqueResultMap(mapper, resultMap, resultMapId)) {
            return Optional.empty();
        }

        Map<TableIdentity, ResolvedTable> referencedTables = new LinkedHashMap<>();
        boolean foundStatement = false;
        for (XmlTag statement : mapper.getSubTags()) {
            ProgressManager.checkCanceled();
            if (!"select".equals(statement.getName())
                    || !MyBatisXmlModel.isStatement(statement)
                    || !referencesResultMap(
                            statement.getAttributeValue("resultMap"),
                            namespace,
                            resultMapId)) {
                continue;
            }
            foundStatement = true;
            MyBatisSqlPsiResult parsed = MyBatisSqlPsiService
                    .getInstance(resultMap.getProject())
                    .parse(statement);
            if (!(parsed instanceof MyBatisSqlPsiResult.Ready ready)
                    || hasBlockingSyntaxProblem(ready)) {
                return Optional.empty();
            }
            MyBatisSqlSchemaAnalysis analysis = MyBatisSqlSchemaAnalyzer.analyze(
                    ready,
                    snapshots);
            List<MyBatisSqlSymbolOccurrence> tables = analysis.occurrences().stream()
                    .filter(occurrence -> occurrence.kind() == MyBatisSqlSymbolKind.TABLE)
                    .toList();
            if (!analysis.metadataComplete() || tables.isEmpty()) {
                return Optional.empty();
            }
            for (MyBatisSqlSymbolOccurrence occurrence : tables) {
                ProgressManager.checkCanceled();
                if (occurrence.status() != MyBatisSqlSymbolStatus.RESOLVED
                        || occurrence.candidates().size() != 1) {
                    return Optional.empty();
                }
                List<ResolvedTable> matches = findCandidate(
                        snapshots,
                        occurrence.candidates().getFirst());
                if (matches.size() != 1) {
                    return Optional.empty();
                }
                ResolvedTable match = matches.getFirst();
                referencedTables.put(match.identity(), match);
            }
        }
        return foundStatement && referencedTables.size() == 1
                ? Optional.of(referencedTables.values().iterator().next())
                : Optional.empty();
    }

    private static boolean referencesResultMap(
            @Nullable String value,
            @NotNull String namespace,
            @NotNull String id) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String qualifiedId = namespace + '.' + id;
        for (String token : value.trim().split("[,\\s]+")) {
            ProgressManager.checkCanceled();
            if (id.equals(token) || qualifiedId.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUniqueResultMap(
            @NotNull XmlTag mapper,
            @NotNull XmlTag expected,
            @NotNull String id) {
        XmlTag match = null;
        for (XmlTag child : mapper.getSubTags()) {
            ProgressManager.checkCanceled();
            if (!"resultMap".equals(child.getName())
                    || !id.equals(staticValue(child.getAttributeValue("id")))) {
                continue;
            }
            if (match != null) {
                return false;
            }
            match = child;
        }
        return match == expected;
    }

    private static boolean hasBlockingSyntaxProblem(
            @NotNull MyBatisSqlPsiResult.Ready ready) {
        boolean malformedPlaceholder = ready.virtualSql().diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code()
                        == MyBatisVirtualSqlDiagnosticCode.MALFORMED_PARAMETER_PLACEHOLDER);
        return malformedPlaceholder || !PsiTreeUtil.findChildrenOfType(
                ready.psiFile(),
                PsiErrorElement.class).isEmpty();
    }

    private static @NotNull List<ResolvedTable> findCandidate(
            @NotNull List<MyBatisDatabaseSnapshot> snapshots,
            @NotNull String candidateName) {
        java.util.ArrayList<ResolvedTable> matches = new java.util.ArrayList<>();
        for (MyBatisDatabaseSnapshot snapshot : snapshots) {
            ProgressManager.checkCanceled();
            for (MyBatisDatabaseTable table : snapshot.tables()) {
                ProgressManager.checkCanceled();
                if (candidateName.equals(candidateName(snapshot, table))) {
                    matches.add(new ResolvedTable(snapshot, table));
                }
            }
        }
        return List.copyOf(matches);
    }

    private static @NotNull String candidateName(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table) {
        return snapshot.displayName() + ':'
                + table.schema().map(value -> value + '.').orElse("")
                + table.name();
    }

    private static @Nullable String staticValue(@Nullable String value) {
        if (value == null || value.isBlank()
                || value.contains("${") || value.contains("#{")
                || value.indexOf(',') >= 0) {
            return null;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isWhitespace(codePoint)) {
                return null;
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    public record ResolvedTable(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @NotNull MyBatisDatabaseTable table) {
        public @NotNull TableIdentity identity() {
            return new TableIdentity(
                    snapshot.dataSourceId(),
                    snapshot.displayName(),
                    snapshot.dialect(),
                    snapshot.modificationCount(),
                    table);
        }
    }

    /**
     * Quick Fix 用于执行前复核的元数据世代与目标表指纹。
     */
    public record TableIdentity(
            @NotNull String dataSourceId,
            @NotNull String displayName,
            @NotNull MyBatisSqlDialect dialect,
            long modificationCount,
            @NotNull MyBatisDatabaseTable table) {
    }
}
