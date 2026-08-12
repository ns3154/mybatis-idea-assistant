package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationNames;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只解析单条 SELECT 的顶层显式投影，不猜测星号和无别名表达式。
 */
public final class MyBatisSelectProjectionParser {
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile(
            "(?i)(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\""
                    + "|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\])"
                    + "(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\""
                    + "|`(?:[^`]|``)+`|\\[(?:[^\\]]|\\]\\])+\\]))*");

    private MyBatisSelectProjectionParser() {
    }

    public static @NotNull MyBatisSelectProjectionResult parse(@NotNull String sql) {
        Scan scan = scan(sql);
        if (scan.failure != null) {
            return new MyBatisSelectProjectionResult.Failure(
                    scan.failureOffset, scan.failure);
        }
        if (scan.selectStart < 0 || scan.fromStart < 0) {
            return failure(0, MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.select.from.missing"));
        }
        List<Range> ranges = new ArrayList<>(scan.commas.size() + 1);
        int start = scan.selectEnd;
        for (int comma : scan.commas) {
            if (comma > start && comma < scan.fromStart) {
                ranges.add(new Range(start, comma));
                start = comma + 1;
            }
        }
        ranges.add(new Range(start, scan.fromStart));
        List<MyBatisSelectColumn> columns = new ArrayList<>(ranges.size());
        Set<String> properties = new HashSet<>();
        for (Range range : ranges) {
            ProgressManager.checkCanceled();
            String item = sql.substring(range.start, range.end).strip();
            if (item.isEmpty()) {
                return failure(range.start, MyBatisAssistantBundle.message(
                        "sqltool.conversion.error.select.projection.empty.column"));
            }
            String label = label(item);
            if (label == null) {
                return failure(range.start,
                        MyBatisAssistantBundle.message(
                                "sqltool.conversion.error.select.projection.alias"));
            }
            String property = MyBatisGenerationNames.lowerCamel(label);
            if (!properties.add(property)) {
                return failure(range.start, MyBatisAssistantBundle.message(
                        "sqltool.conversion.error.select.property.duplicate", property));
            }
            columns.add(new MyBatisSelectColumn(label, property));
        }
        return columns.isEmpty() ? failure(scan.selectEnd, MyBatisAssistantBundle.message(
                "sqltool.conversion.error.select.projection.empty"))
                : new MyBatisSelectProjectionResult.Success(columns);
    }

    private static String label(String item) {
        int asOffset = topLevelAs(item);
        String candidate;
        if (asOffset >= 0) {
            candidate = item.substring(asOffset + 2).strip();
            if (candidate.isEmpty() || !isOneIdentifier(candidate)) {
                return null;
            }
        } else {
            if (!SIMPLE_IDENTIFIER.matcher(item).matches() || item.endsWith(".*")) {
                return null;
            }
            candidate = lastIdentifier(item);
        }
        String unquoted = unquote(candidate);
        return unquoted.isBlank() ? null : unquoted;
    }

    private static int topLevelAs(String item) {
        Matcher matcher = Pattern.compile("(?i)\\s+AS\\s+").matcher(item);
        int result = -1;
        while (matcher.find()) {
            ProgressManager.checkCanceled();
            result = matcher.start() + matcher.group().toUpperCase(Locale.ROOT).indexOf("AS");
        }
        return result;
    }

    private static boolean isOneIdentifier(String value) {
        return SIMPLE_IDENTIFIER.matcher(value).matches() && !value.contains(".");
    }

    private static String lastIdentifier(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1).replace("]]", "]");
        }
        return value;
    }

    private static Scan scan(String sql) {
        Scan scan = new Scan();
        State state = State.NORMAL;
        String dollarDelimiter = null;
        int blockDepth = 0;
        int parenthesisDepth = 0;
        for (int index = 0; index < sql.length(); index++) {
            if ((index & 255) == 0) {
                ProgressManager.checkCanceled();
            }
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                String delimiter = dollarDelimiter(sql, index);
                if (delimiter != null) {
                    dollarDelimiter = delimiter;
                    state = State.DOLLAR;
                    index += delimiter.length() - 1;
                } else if (current == '\'') {
                    state = State.SINGLE;
                } else if (current == '"') {
                    state = State.DOUBLE;
                } else if (current == '`') {
                    state = State.BACKTICK;
                } else if (current == '[') {
                    state = State.BRACKET;
                } else if (current == '-' && next == '-') {
                    state = State.LINE_COMMENT;
                    index++;
                } else if (current == '#') {
                    state = State.LINE_COMMENT;
                } else if (current == '/' && next == '*') {
                    state = State.BLOCK_COMMENT;
                    blockDepth = 1;
                    index++;
                } else if (current == '(') {
                    parenthesisDepth++;
                } else if (current == ')') {
                    if (--parenthesisDepth < 0) {
                        return scan.fail(index, MyBatisAssistantBundle.message(
                                "sqltool.conversion.error.sql.parenthesis.mismatch"));
                    }
                } else if (parenthesisDepth == 0) {
                    if (scan.selectStart < 0 && wordAt(sql, index, "SELECT")) {
                        scan.selectStart = index;
                        scan.selectEnd = index + 6;
                        index += 5;
                    } else if (scan.selectStart >= 0 && scan.fromStart < 0
                            && wordAt(sql, index, "FROM")) {
                        scan.fromStart = index;
                        index += 3;
                    } else if (current == ',' && scan.selectStart >= 0
                            && scan.fromStart < 0) {
                        scan.commas.add(index);
                    }
                }
                continue;
            }
            switch (state) {
                case SINGLE -> {
                    if (current == '\\' && next != '\0') {
                        index++;
                    } else if (current == '\'' && next == '\'') {
                        index++;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                    }
                }
                case DOUBLE -> {
                    if (current == '"' && next == '"') {
                        index++;
                    } else if (current == '"') {
                        state = State.NORMAL;
                    }
                }
                case BACKTICK -> {
                    if (current == '`' && next == '`') {
                        index++;
                    } else if (current == '`') {
                        state = State.NORMAL;
                    }
                }
                case BRACKET -> {
                    if (current == ']' && next == ']') {
                        index++;
                    } else if (current == ']') {
                        state = State.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') {
                        state = State.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') {
                        blockDepth++;
                        index++;
                    } else if (current == '*' && next == '/') {
                        blockDepth--;
                        index++;
                        if (blockDepth == 0) {
                            state = State.NORMAL;
                        }
                    }
                }
                case DOLLAR -> {
                    if (sql.startsWith(dollarDelimiter, index)) {
                        index += dollarDelimiter.length() - 1;
                        state = State.NORMAL;
                        dollarDelimiter = null;
                    }
                }
                case NORMAL -> throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "sqltool.log.error.scan.state.invalid"));
            }
        }
        if (state != State.NORMAL && state != State.LINE_COMMENT) {
            return scan.fail(sql.length(), MyBatisAssistantBundle.message(
                    "sqltool.log.error.sql.unclosed"));
        }
        if (parenthesisDepth != 0) {
            return scan.fail(sql.length(), MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.sql.parenthesis.mismatch"));
        }
        return scan;
    }

    private static boolean wordAt(String sql, int start, String word) {
        int end = start + word.length();
        if (end > sql.length() || !sql.regionMatches(true, start, word, 0, word.length())) {
            return false;
        }
        return (start == 0 || !Character.isJavaIdentifierPart(sql.charAt(start - 1)))
                && (end == sql.length() || !Character.isJavaIdentifierPart(sql.charAt(end)));
    }

    private static String dollarDelimiter(String sql, int start) {
        if (sql.charAt(start) != '$') {
            return null;
        }
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        String tag = sql.substring(start + 1, end);
        return tag.isEmpty() || tag.matches("[A-Za-z_][A-Za-z0-9_]*")
                ? sql.substring(start, end + 1) : null;
    }

    private static MyBatisSelectProjectionResult.Failure failure(int offset, String message) {
        return new MyBatisSelectProjectionResult.Failure(offset, message);
    }

    private enum State {
        NORMAL,
        SINGLE,
        DOUBLE,
        BACKTICK,
        BRACKET,
        LINE_COMMENT,
        BLOCK_COMMENT,
        DOLLAR
    }

    private record Range(int start, int end) {
    }

    private static final class Scan {
        private int selectStart = -1;
        private int selectEnd = -1;
        private int fromStart = -1;
        private final List<Integer> commas = new ArrayList<>();
        private String failure;
        private int failureOffset;

        private Scan fail(int offset, String message) {
            failureOffset = offset;
            failure = message;
            return this;
        }
    }
}
