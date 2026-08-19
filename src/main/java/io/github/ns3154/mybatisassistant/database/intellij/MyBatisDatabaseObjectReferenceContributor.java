package io.github.ns3154.mybatisassistant.database.intellij;

import com.intellij.database.psi.DbDataSource;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.ProcessingContext;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapping;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.sql.MyBatisVirtualSqlDiagnosticCode;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlPsiResult;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlPsiService;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSchemaAnalysis;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSchemaAnalyzer;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolOccurrence;
import io.github.ns3154.mybatisassistant.sql.intellij.MyBatisSqlSymbolStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为 Mapper XML 中唯一解析的表列注册 Database Tools 引用。
 */
public final class MyBatisDatabaseObjectReferenceContributor
        extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlToken.class)
                        .withElementType(XmlTokenType.XML_DATA_CHARACTERS),
                new DatabaseObjectReferenceProvider());
    }

    private static final class DatabaseObjectReferenceProvider extends PsiReferenceProvider {
        @Override
        public @NotNull PsiReference[] getReferencesByElement(
                @NotNull PsiElement element,
                @NotNull ProcessingContext context) {
            ProgressManager.checkCanceled();
            if (!(element instanceof XmlToken token)) {
                return PsiReference.EMPTY_ARRAY;
            }
            Project project = token.getProject();
            if (!token.isValid()
                    || project.isDisposed()
                    || !project.isOpen()
                    || DumbService.isDumb(project)) {
                return PsiReference.EMPTY_ARRAY;
            }
            try {
                return references(token);
            } catch (IndexNotReadyException ignored) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
    }

    private static @NotNull PsiReference[] references(@NotNull XmlToken token) {
        XmlTag statement = statement(token);
        PsiFile sourceFile = token.getContainingFile();
        if (statement == null || sourceFile == null || sourceFile.getVirtualFile() == null) {
            return PsiReference.EMPTY_ARRAY;
        }
        MyBatisDatabaseMetadataService metadata = MyBatisDatabaseMetadataService
                .getInstance(token.getProject());
        var latest = metadata.latest();
        if (latest.isEmpty()) {
            metadata.refresh();
            return PsiReference.EMPTY_ARRAY;
        }
        List<MyBatisDatabaseSnapshot> snapshots = databaseToolsSnapshots(
                token.getProject(),
                latest.orElseThrow().snapshots());
        if (snapshots.isEmpty()) {
            return PsiReference.EMPTY_ARRAY;
        }
        MyBatisSqlPsiResult result = MyBatisSqlPsiService
                .getInstance(token.getProject())
                .parse(statement);
        if (!(result instanceof MyBatisSqlPsiResult.Ready ready)
                || hasBlockingSyntaxProblem(ready)) {
            return PsiReference.EMPTY_ARRAY;
        }
        MyBatisSqlSchemaAnalysis analysis = MyBatisSqlSchemaAnalyzer.analyze(
                ready,
                snapshots);
        if (!analysis.metadataComplete()) {
            return PsiReference.EMPTY_ARRAY;
        }
        Map<ReferenceKey, PsiReference> references = new LinkedHashMap<>();
        for (MyBatisSqlSymbolOccurrence occurrence : analysis.occurrences()) {
            ProgressManager.checkCanceled();
            if (occurrence.status() != MyBatisSqlSymbolStatus.RESOLVED) {
                continue;
            }
            var target = MyBatisDatabaseObjectTarget.fromOccurrence(
                    occurrence,
                    snapshots);
            if (target.isEmpty()) {
                continue;
            }
            TextRange range = sourceRange(token, sourceFile, ready, occurrence);
            if (range != null) {
                ReferenceKey key = new ReferenceKey(range, target.orElseThrow());
                references.putIfAbsent(
                        key,
                        new MyBatisDatabaseObjectReference(
                                token,
                                range,
                                target.orElseThrow()));
            }
        }
        List<PsiReference> sorted = new ArrayList<>(references.values());
        sorted.sort(Comparator.comparingInt(reference ->
                reference.getRangeInElement().getStartOffset()));
        return sorted.toArray(PsiReference.EMPTY_ARRAY);
    }

    private static @NotNull List<MyBatisDatabaseSnapshot> databaseToolsSnapshots(
            @NotNull Project project,
            @NotNull List<MyBatisDatabaseSnapshot> snapshots) {
        Map<String, DbDataSource> readyDataSources = new HashMap<>();
        for (DbDataSource dataSource : DbPsiFacade.getInstance(project).getDataSources()) {
            ProgressManager.checkCanceled();
            if (dataSource.isValid() && !dataSource.isLoading()) {
                readyDataSources.put(dataSource.getUniqueId(), dataSource);
            }
        }
        List<MyBatisDatabaseSnapshot> matching = new ArrayList<>();
        for (MyBatisDatabaseSnapshot snapshot : snapshots) {
            ProgressManager.checkCanceled();
            if (matches(snapshot, readyDataSources.get(snapshot.dataSourceId()))) {
                matching.add(snapshot);
            }
        }
        return List.copyOf(matching);
    }

    private static boolean matches(
            @NotNull MyBatisDatabaseSnapshot snapshot,
            @Nullable DbDataSource dataSource) {
        return dataSource != null
                && dataSource.isValid()
                && snapshot.displayName().equals(dataSource.getName())
                && snapshot.modificationCount()
                        == Math.max(0, dataSource.getModificationTracker()
                                .getModificationCount());
    }

    private static @Nullable TextRange sourceRange(
            @NotNull XmlToken token,
            @NotNull PsiFile sourceFile,
            @NotNull MyBatisSqlPsiResult.Ready ready,
            @NotNull MyBatisSqlSymbolOccurrence occurrence) {
        List<MyBatisSourceMapping> mappings = ready.virtualSql()
                .mappedText()
                .sourceMap()
                .sourceMappings(occurrence.virtualRange());
        if (mappings.size() != 1) {
            return null;
        }
        MyBatisSourceMapping mapping = mappings.getFirst();
        String fileUrl = sourceFile.getVirtualFile().getUrl();
        int sourceStart = mapping.sourceRange().range().startOffset();
        int sourceEnd = mapping.sourceRange().range().endOffset();
        if (mapping.kind() != MyBatisSourceMapKind.EXACT
                || !fileUrl.equals(mapping.sourceRange().fileUrl())
                || !token.getTextRange().containsRange(sourceStart, sourceEnd)) {
            return null;
        }
        return new TextRange(
                sourceStart - token.getTextRange().getStartOffset(),
                sourceEnd - token.getTextRange().getStartOffset());
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

    private static @Nullable XmlTag statement(@NotNull XmlToken token) {
        XmlTag tag = PsiTreeUtil.getParentOfType(token, XmlTag.class, false);
        while (tag != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(tag)) {
                return tag;
            }
            tag = tag.getParentTag();
        }
        return null;
    }

    private record ReferenceKey(
            @NotNull TextRange range,
            @NotNull MyBatisDatabaseObjectTarget target) {
    }
}
