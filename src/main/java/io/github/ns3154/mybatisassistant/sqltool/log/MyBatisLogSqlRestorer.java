package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在本地配对 MyBatis Preparing/Parameters 日志并安全还原 SQL。
 */
public final class MyBatisLogSqlRestorer {
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(.*?)(=+>)\\s+(Preparing|Parameters):\\s?(.*)$");
    private static final Pattern THREAD = Pattern.compile("\\[([^]\\r\\n]+)]");
    private static final Pattern LEADING_TIME = Pattern.compile(
            "^\\s*\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\s*");
    private static final Pattern LEADING_LEVEL = Pattern.compile(
            "^(?:TRACE|DEBUG|INFO|WARN|ERROR)\\s+", Pattern.CASE_INSENSITIVE);

    private MyBatisLogSqlRestorer() {
    }

    public static @NotNull MyBatisLogRestoreReport restore(@NotNull String logText) {
        if (logText.length() > MAX_INPUT_BYTES
                || logText.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return new MyBatisLogRestoreReport(List.of(), List.of(new MyBatisLogDiagnostic(
                    MyBatisLogDiagnosticCode.INPUT_TOO_LARGE,
                    0,
                    MyBatisAssistantBundle.message("sqltool.log.error.input.too.large"))));
        }
        List<MyBatisRestoredStatement> restored = new ArrayList<>();
        List<MyBatisLogDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Deque<PreparingEntry>> pending = new LinkedHashMap<>();
        String[] lines = logText.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            ProgressManager.checkCanceled();
            Matcher matcher = LOG_LINE.matcher(lines[index]);
            if (!matcher.matches()) {
                continue;
            }
            int lineNumber = index + 1;
            String context = context(matcher.group(1));
            if ("Preparing".equals(matcher.group(3))) {
                pending.computeIfAbsent(context, ignored -> new ArrayDeque<>())
                        .addLast(new PreparingEntry(lineNumber, matcher.group(4)));
            } else {
                Deque<PreparingEntry> entries = pending.get(context);
                if (entries == null || entries.isEmpty()) {
                    diagnostics.add(new MyBatisLogDiagnostic(
                            MyBatisLogDiagnosticCode.PARAMETERS_WITHOUT_PREPARING,
                            lineNumber,
                            MyBatisAssistantBundle.message(
                                    "sqltool.log.error.preparing.missing", lineNumber)));
                    continue;
                }
                PreparingEntry preparing = entries.removeFirst();
                restorePair(
                        context,
                        preparing,
                        lineNumber,
                        matcher.group(4),
                        restored,
                        diagnostics);
            }
        }
        for (Deque<PreparingEntry> entries : pending.values()) {
            for (PreparingEntry entry : entries) {
                diagnostics.add(new MyBatisLogDiagnostic(
                        MyBatisLogDiagnosticCode.PREPARING_WITHOUT_PARAMETERS,
                        entry.lineNumber,
                        MyBatisAssistantBundle.message(
                                "sqltool.log.error.parameters.missing", entry.lineNumber)));
            }
        }
        return new MyBatisLogRestoreReport(restored, diagnostics);
    }

    private static void restorePair(
            String context,
            PreparingEntry preparing,
            int parametersLine,
            String parameters,
            List<MyBatisRestoredStatement> restored,
            List<MyBatisLogDiagnostic> diagnostics) {
        MyBatisSqlLexicalScanner.ScanResult initial;
        try {
            initial = MyBatisSqlLexicalScanner.scan(preparing.sql, List.of());
        } catch (MyBatisSqlLexicalScanner.MalformedSqlException malformed) {
            diagnostics.add(new MyBatisLogDiagnostic(
                    MyBatisLogDiagnosticCode.MALFORMED_SQL,
                    preparing.lineNumber,
                    MyBatisAssistantBundle.message("sqltool.log.error.sql.incomplete")));
            return;
        }
        MyBatisLogParameterParser.ParseResult parsed = MyBatisLogParameterParser.parse(
                parameters, initial.placeholderCount());
        if (!parsed.success()) {
            diagnostics.add(new MyBatisLogDiagnostic(
                    parsed.diagnosticCode(),
                    parametersLine,
                    MyBatisAssistantBundle.message(
                            "sqltool.log.error.line.detail", parametersLine, parsed.message())));
            return;
        }
        try {
            MyBatisSqlLexicalScanner.ScanResult replaced = MyBatisSqlLexicalScanner.scan(
                    preparing.sql, parsed.literals());
            if (replaced.placeholderCount() != parsed.literals().size()) {
                diagnostics.add(new MyBatisLogDiagnostic(
                        MyBatisLogDiagnosticCode.PLACEHOLDER_COUNT_MISMATCH,
                        parametersLine,
                        MyBatisAssistantBundle.message(
                                "sqltool.log.error.restore.count.mismatch")));
                return;
            }
            String sql = replaced.renderedSql();
            restored.add(new MyBatisRestoredStatement(
                    context,
                    preparing.lineNumber,
                    parametersLine,
                    sql,
                    MyBatisSqlRiskClassifier.assess(sql)));
        } catch (MyBatisSqlLexicalScanner.MalformedSqlException impossible) {
            diagnostics.add(new MyBatisLogDiagnostic(
                    MyBatisLogDiagnosticCode.MALFORMED_SQL,
                    preparing.lineNumber,
                    MyBatisAssistantBundle.message("sqltool.log.error.sql.incomplete")));
        }
    }

    private static String context(String prefix) {
        Matcher matcher = THREAD.matcher(prefix);
        String lastThread = null;
        while (matcher.find()) {
            lastThread = matcher.group(1).trim();
        }
        if (lastThread != null && !lastThread.isEmpty()) {
            return lastThread;
        }
        String normalized = LEADING_TIME.matcher(prefix).replaceFirst("");
        normalized = LEADING_LEVEL.matcher(normalized.trim()).replaceFirst("").trim();
        return normalized.isEmpty()
                ? MyBatisAssistantBundle.message("sqltool.log.context.default") : normalized;
    }

    private record PreparingEntry(int lineNumber, String sql) {
    }
}
