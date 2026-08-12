package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.sql.psi.SqlAsExpression;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
import io.github.ns3154.mybatisassistant.reference.MyBatisAnnotationSqlSupport;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只消费已完成元数据快照的 SQL 表列补全，绝不在补全线程等待数据库。
 */
public final class MyBatisSqlCompletionContributor extends CompletionContributor {
    private static final List<String> COMMON_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN",
            "INNER JOIN", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET",
            "INSERT", "UPDATE", "DELETE", "VALUES", "SET", "AND", "OR", "ON",
            "AS", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "NULL",
            "IS", "IN", "EXISTS", "LIKE");
    private static final List<String> COMMON_FUNCTIONS = List.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "LOWER",
            "UPPER", "LENGTH", "CURRENT_DATE", "CURRENT_TIMESTAMP");

    @Override
    public void fillCompletionVariants(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {
        if (addResultMapColumnCompletions(parameters, result)) {
            return;
        }
        addMetadataCompletions(parameters, result);
    }

    private static boolean addResultMapColumnCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {
        ProgressManager.checkCanceled();
        PsiElement position = parameters.getOriginalPosition();
        if (position == null) {
            position = parameters.getPosition();
        }
        XmlAttributeValue value = position instanceof XmlAttributeValue direct
                ? direct
                : PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false);
        if (value == null || !(value.getParent() instanceof XmlAttribute attribute)
                || !"column".equals(attribute.getName())
                || !(attribute.getParent() instanceof XmlTag mapping)
                || !MyBatisResultMapMappingPlanner.isDirectColumnMapping(mapping)) {
            return false;
        }
        XmlTag resultMap = mapping.getParentTag();
        if (resultMap == null || !isSimpleStaticColumnValue(value.getValue())) {
            return true;
        }
        var latest = MyBatisDatabaseMetadataService
                .getInstance(position.getProject())
                .latest();
        if (latest.isEmpty()) {
            return true;
        }
        var resolved = MyBatisResultMapSchemaResolver.resolve(
                resultMap,
                latest.orElseThrow().snapshots());
        if (resolved.isEmpty()) {
            return true;
        }
        Map<String, LookupElementBuilder> candidates = new LinkedHashMap<>();
        MyBatisDatabaseTable table = resolved.orElseThrow().table();
        for (MyBatisDatabaseColumn column : table.columns()) {
            ProgressManager.checkCanceled();
            String key = column.name().toLowerCase(java.util.Locale.ROOT);
            if (candidates.containsKey(key)) {
                return true;
            }
            candidates.put(
                    key,
                    LookupElementBuilder.create(column.name())
                            .withTypeText(MyBatisAssistantBundle.message(
                                    "completion.sql.column.table",
                                    table.name()), true));
        }
        candidates.values().forEach(result::addElement);
        return true;
    }

    private static boolean isSimpleStaticColumnValue(@NotNull String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.contains("${") || value.contains("#{")
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0
                || value.indexOf(',') >= 0) {
            return false;
        }
        int first = value.codePointAt(0);
        if (!Character.isUnicodeIdentifierStart(first) && first != '_') {
            return false;
        }
        for (int offset = Character.charCount(first); offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isUnicodeIdentifierPart(codePoint)
                    && codePoint != '_' && codePoint != '$') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static void addMetadataCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {
        ProgressManager.checkCanceled();
        PsiElement position = parameters.getPosition();
        XmlTag statement = statement(parameters);
        AnnotationContext annotation = annotationContext(parameters);
        if (statement == null && annotation == null) {
            return;
        }
        CompletionResultSet output = annotation == null
                ? result
                : result.withPrefixMatcher(annotationPrefix(annotation));
        Map<String, LookupElementBuilder> candidates = new LinkedHashMap<>();
        COMMON_KEYWORDS.forEach(keyword -> candidates.put(
                "keyword:" + keyword,
                LookupElementBuilder.create(keyword).withTypeText(MyBatisAssistantBundle.message(
                        "completion.sql.keyword"), true)));
        COMMON_FUNCTIONS.forEach(function -> candidates.put(
                "function:" + function,
                LookupElementBuilder.create(function).withTypeText(MyBatisAssistantBundle.message(
                        "completion.sql.function"), true)));
        if (statement != null) {
            addAliases(statement, candidates);
        } else {
            addAliases(position.getContainingFile(), candidates);
            addAnnotationParameters(annotation, candidates);
        }
        MyBatisDatabaseMetadataService service = MyBatisDatabaseMetadataService
                .getInstance(position.getProject());
        var latest = service.latest();
        if (latest.isEmpty()) {
            service.refresh();
            candidates.values().forEach(output::addElement);
            return;
        }
        for (MyBatisDatabaseSnapshot snapshot : latest.orElseThrow().snapshots()) {
            ProgressManager.checkCanceled();
            if (snapshot.freshness() != MyBatisMetadataFreshness.READY) {
                continue;
            }
            for (MyBatisDatabaseTable table : snapshot.tables()) {
                ProgressManager.checkCanceled();
                String tableContext = table.schema().map(schema -> MyBatisAssistantBundle.message(
                                "completion.sql.table.schema", schema))
                        .orElseGet(() -> MyBatisAssistantBundle.message("completion.sql.table"));
                candidates.putIfAbsent(
                        "table:" + table.name().toLowerCase(java.util.Locale.ROOT),
                        LookupElementBuilder.create(table.name())
                                .withTypeText(tableContext, true));
                for (MyBatisDatabaseColumn column : table.columns()) {
                    ProgressManager.checkCanceled();
                    candidates.putIfAbsent(
                            "column:" + column.name().toLowerCase(java.util.Locale.ROOT),
                            LookupElementBuilder.create(column.name())
                                    .withTypeText(MyBatisAssistantBundle.message(
                                            "completion.sql.column.table", table.name()), true));
                }
            }
        }
        candidates.values().forEach(output::addElement);
    }

    private static void addAliases(
            @NotNull XmlTag statement,
            @NotNull Map<String, LookupElementBuilder> candidates) {
        MyBatisSqlPsiResult parsed = MyBatisSqlPsiService
                .getInstance(statement.getProject())
                .parse(statement);
        if (!(parsed instanceof MyBatisSqlPsiResult.Ready ready)) {
            return;
        }
        addAliases(ready.psiFile(), candidates);
    }

    private static void addAliases(
            @NotNull PsiFile sqlFile,
            @NotNull Map<String, LookupElementBuilder> candidates) {
        for (SqlAsExpression expression : PsiTreeUtil.findChildrenOfType(
                sqlFile,
                SqlAsExpression.class)) {
            ProgressManager.checkCanceled();
            if (expression.getNameElement() == null
                    || expression.getNameElement().getName() == null) {
                continue;
            }
            String alias = expression.getNameElement().getName();
            candidates.putIfAbsent(
                    "alias:" + alias,
                    LookupElementBuilder.create(alias).withTypeText(MyBatisAssistantBundle.message(
                            "completion.sql.alias"), true));
        }
    }

    private static void addAnnotationParameters(
            @NotNull AnnotationContext annotation,
            @NotNull Map<String, LookupElementBuilder> candidates) {
        for (String variant : MyBatisAnnotationSqlSupport.completionVariants(
                annotation.literal(),
                annotation.offsetInLiteral())) {
            ProgressManager.checkCanceled();
            candidates.putIfAbsent(
                    "parameter:" + variant,
                    LookupElementBuilder.create(variant)
                            .withTypeText(MyBatisAssistantBundle.message(
                                    "completion.mybatis.parameter"), true));
        }
    }

    private static @NotNull String annotationPrefix(
            @NotNull AnnotationContext annotation) {
        MyBatisAnnotationSqlSupport.AnnotationSqlLiteral sql =
                MyBatisAnnotationSqlSupport.inspect(annotation.literal());
        if (sql == null) {
            return "";
        }
        int rawOffset = Math.max(0, Math.min(
                annotation.offsetInLiteral() - sql.valueRange().getStartOffset(),
                sql.rawSql().length()));
        String prefix = sql.rawSql().substring(0, rawOffset);
        int placeholderStart = Math.max(prefix.lastIndexOf("#{"), prefix.lastIndexOf("${"));
        if (placeholderStart < 0 || prefix.lastIndexOf('}') > placeholderStart) {
            return "";
        }
        int segmentStart = Math.max(prefix.lastIndexOf('.'), placeholderStart + 1) + 1;
        return prefix.substring(Math.min(segmentStart, prefix.length()));
    }

    private static XmlTag statement(@NotNull CompletionParameters parameters) {
        PsiElement position = parameters.getOriginalPosition();
        if (position == null) {
            position = parameters.getPosition();
        }
        XmlTag direct = statementFromXmlText(position);
        if (direct != null) {
            return direct;
        }
        PsiFile injectedFile = position.getContainingFile();
        PsiLanguageInjectionHost host = InjectedLanguageManager
                .getInstance(position.getProject())
                .getInjectionHost(injectedFile);
        if (host instanceof XmlText xmlText && statement(xmlText) != null) {
            return statement(xmlText);
        }
        Document document = parameters.getEditor().getDocument();
        if (!(document instanceof DocumentWindow window) || !window.isValid()) {
            return null;
        }
        PsiFile topLevel = PsiDocumentManager.getInstance(position.getProject())
                .getPsiFile(window.getDelegate());
        if (topLevel == null || topLevel.getTextLength() == 0) {
            return null;
        }
        int injectedOffset = Math.min(parameters.getOffset(), document.getTextLength());
        int hostOffset = Math.min(
                window.injectedToHost(injectedOffset),
                topLevel.getTextLength() - 1);
        return statementFromXmlText(topLevel.findElementAt(Math.max(0, hostOffset)));
    }

    private static AnnotationContext annotationContext(
            @NotNull CompletionParameters parameters) {
        PsiElement position = parameters.getOriginalPosition();
        if (position == null) {
            position = parameters.getPosition();
        }
        PsiLanguageInjectionHost injectionHost = InjectedLanguageManager
                .getInstance(position.getProject())
                .getInjectionHost(position.getContainingFile());
        if (injectionHost instanceof PsiLiteralExpression literal) {
            int hostOffset = hostOffset(parameters);
            return MyBatisAnnotationSqlSupport.inspect(literal) == null
                    ? null
                    : new AnnotationContext(literal, hostOffset - literal.getTextOffset());
        }
        Document document = parameters.getEditor().getDocument();
        if (document instanceof DocumentWindow window && window.isValid()) {
            PsiFile topLevel = PsiDocumentManager.getInstance(position.getProject())
                    .getPsiFile(window.getDelegate());
            int hostOffset = hostOffset(parameters);
            PsiElement hostElement = topLevel == null || topLevel.getTextLength() == 0
                    ? null
                    : topLevel.findElementAt(Math.min(
                            Math.max(0, hostOffset),
                            topLevel.getTextLength() - 1));
            PsiLiteralExpression hostLiteral = hostElement instanceof PsiLiteralExpression direct
                    ? direct
                    : PsiTreeUtil.getParentOfType(
                            hostElement,
                            PsiLiteralExpression.class,
                            false);
            if (hostLiteral != null
                    && MyBatisAnnotationSqlSupport.inspect(hostLiteral) != null) {
                return new AnnotationContext(
                        hostLiteral,
                        hostOffset - hostLiteral.getTextOffset());
            }
        }
        PsiLiteralExpression literal = position instanceof PsiLiteralExpression direct
                ? direct
                : PsiTreeUtil.getParentOfType(position, PsiLiteralExpression.class, false);
        if (literal == null || MyBatisAnnotationSqlSupport.inspect(literal) == null) {
            return null;
        }
        return new AnnotationContext(literal, hostOffset(parameters) - literal.getTextOffset());
    }

    private static int hostOffset(@NotNull CompletionParameters parameters) {
        Document document = parameters.getEditor().getDocument();
        if (document instanceof DocumentWindow window && window.isValid()) {
            return window.injectedToHost(Math.min(
                    parameters.getOffset(),
                    document.getTextLength()));
        }
        return parameters.getOffset();
    }

    private static XmlTag statementFromXmlText(PsiElement element) {
        if (element == null) {
            return null;
        }
        XmlText xmlText = element instanceof XmlText text
                ? text
                : PsiTreeUtil.getParentOfType(element, XmlText.class, false);
        return xmlText == null ? null : statement(xmlText);
    }

    private static XmlTag statement(PsiElement element) {
        if (element == null) {
            return null;
        }
        XmlTag tag = element instanceof XmlTag xmlTag
                ? xmlTag
                : PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
        while (tag != null) {
            ProgressManager.checkCanceled();
            if (MyBatisXmlModel.isStatement(tag)) {
                return tag;
            }
            tag = tag.getParentTag();
        }
        return null;
    }

    private record AnnotationContext(
            @NotNull PsiLiteralExpression literal,
            int offsetInLiteral) {
    }
}
