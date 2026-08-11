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
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.sql.psi.SqlAsExpression;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseMetadataService;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseSnapshot;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisMetadataFreshness;
import io.github.ns3154.mybatisassistant.model.MyBatisXmlModel;
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
        addMetadataCompletions(parameters, result);
    }

    private static void addMetadataCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {
        ProgressManager.checkCanceled();
        PsiElement position = parameters.getPosition();
        XmlTag statement = statement(parameters);
        if (statement == null) {
            return;
        }
        Map<String, LookupElementBuilder> candidates = new LinkedHashMap<>();
        COMMON_KEYWORDS.forEach(keyword -> candidates.put(
                "keyword:" + keyword,
                LookupElementBuilder.create(keyword).withTypeText("SQL 关键字", true)));
        COMMON_FUNCTIONS.forEach(function -> candidates.put(
                "function:" + function,
                LookupElementBuilder.create(function).withTypeText("SQL 函数", true)));
        addAliases(statement, candidates);
        MyBatisDatabaseMetadataService service = MyBatisDatabaseMetadataService
                .getInstance(position.getProject());
        var latest = service.latest();
        if (latest.isEmpty()) {
            service.refresh();
            candidates.values().forEach(result::addElement);
            return;
        }
        for (MyBatisDatabaseSnapshot snapshot : latest.orElseThrow().snapshots()) {
            ProgressManager.checkCanceled();
            if (snapshot.freshness() != MyBatisMetadataFreshness.READY) {
                continue;
            }
            for (MyBatisDatabaseTable table : snapshot.tables()) {
                ProgressManager.checkCanceled();
                String tableContext = table.schema().map(schema -> "表 · " + schema)
                        .orElse("表");
                candidates.putIfAbsent(
                        "table:" + table.name().toLowerCase(java.util.Locale.ROOT),
                        LookupElementBuilder.create(table.name())
                                .withTypeText(tableContext, true));
                for (MyBatisDatabaseColumn column : table.columns()) {
                    ProgressManager.checkCanceled();
                    candidates.putIfAbsent(
                            "column:" + column.name().toLowerCase(java.util.Locale.ROOT),
                            LookupElementBuilder.create(column.name())
                                    .withTypeText("列 · " + table.name(), true));
                }
            }
        }
        candidates.values().forEach(result::addElement);
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
        for (SqlAsExpression expression : PsiTreeUtil.findChildrenOfType(
                ready.psiFile(),
                SqlAsExpression.class)) {
            ProgressManager.checkCanceled();
            if (expression.getNameElement() == null
                    || expression.getNameElement().getName() == null) {
                continue;
            }
            String alias = expression.getNameElement().getName();
            candidates.putIfAbsent(
                    "alias:" + alias,
                    LookupElementBuilder.create(alias).withTypeText("SQL 别名", true));
        }
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
}
