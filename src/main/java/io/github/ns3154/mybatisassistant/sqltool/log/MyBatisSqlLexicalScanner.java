package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 识别常见 SQL 引号、注释与 PostgreSQL dollar quote 的字符级扫描器。
 */
final class MyBatisSqlLexicalScanner {
    private MyBatisSqlLexicalScanner() {
    }

    static @NotNull ScanResult scan(
            @NotNull String sql,
            @NotNull List<String> replacements) throws MalformedSqlException {
        StringBuilder rendered = new StringBuilder(sql.length() + replacements.size() * 8);
        StringBuilder visible = new StringBuilder(sql.length());
        List<String> statements = new ArrayList<>();
        State state = State.NORMAL;
        String dollarDelimiter = null;
        int blockCommentDepth = 0;
        int replacementIndex = 0;
        for (int index = 0; index < sql.length(); index++) {
            if ((index & 255) == 0) {
                ProgressManager.checkCanceled();
            }
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                String delimiter = dollarDelimiter(sql, index);
                if (delimiter != null) {
                    rendered.append(delimiter);
                    appendSpaces(visible, delimiter.length());
                    index += delimiter.length() - 1;
                    dollarDelimiter = delimiter;
                    state = State.DOLLAR_QUOTE;
                } else if (current == '\'') {
                    rendered.append(current);
                    visible.append(' ');
                    state = State.SINGLE_QUOTE;
                } else if (current == '"') {
                    rendered.append(current);
                    visible.append(' ');
                    state = State.DOUBLE_QUOTE;
                } else if (current == '`') {
                    rendered.append(current);
                    visible.append(' ');
                    state = State.BACKTICK;
                } else if (current == '[') {
                    rendered.append(current);
                    visible.append(' ');
                    state = State.BRACKET;
                } else if (current == '-' && next == '-') {
                    rendered.append("--");
                    visible.append("  ");
                    index++;
                    state = State.LINE_COMMENT;
                } else if (current == '#') {
                    rendered.append(current);
                    visible.append(' ');
                    state = State.LINE_COMMENT;
                } else if (current == '/' && next == '*') {
                    rendered.append("/*");
                    visible.append("  ");
                    index++;
                    state = State.BLOCK_COMMENT;
                    blockCommentDepth = 1;
                } else if (current == '?') {
                    rendered.append(replacementIndex < replacements.size()
                            ? replacements.get(replacementIndex) : "?");
                    visible.append('?');
                    replacementIndex++;
                } else if (current == ';') {
                    rendered.append(current);
                    finishStatement(visible, statements);
                } else {
                    rendered.append(current);
                    visible.append(current);
                }
                continue;
            }
            rendered.append(current);
            visible.append(current == '\n' || current == '\r' ? current : ' ');
            switch (state) {
                case SINGLE_QUOTE -> {
                    if (current == '\\' && next != '\0') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '\'' && next == '\'') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '\\' && next != '\0') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '"' && next == '"') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '"') {
                        state = State.NORMAL;
                    }
                }
                case BACKTICK -> {
                    if (current == '\\' && next != '\0') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '`' && next == '`') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                    } else if (current == '`') {
                        state = State.NORMAL;
                    }
                }
                case BRACKET -> {
                    if (current == ']' && next == ']') {
                        rendered.append(next);
                        visible.append(' ');
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
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                        blockCommentDepth++;
                    } else if (current == '*' && next == '/') {
                        rendered.append(next);
                        visible.append(' ');
                        index++;
                        blockCommentDepth--;
                        if (blockCommentDepth == 0) {
                            state = State.NORMAL;
                        }
                    }
                }
                case DOLLAR_QUOTE -> {
                    if (sql.startsWith(dollarDelimiter, index)) {
                        int remaining = dollarDelimiter.length() - 1;
                        if (remaining > 0) {
                            rendered.append(sql, index + 1, index + 1 + remaining);
                            appendSpaces(visible, remaining);
                            index += remaining;
                        }
                        state = State.NORMAL;
                        dollarDelimiter = null;
                    }
                }
                case NORMAL -> throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "sqltool.log.error.scan.state.invalid"));
                default -> throw new IllegalStateException(MyBatisAssistantBundle.message(
                        "sqltool.log.error.scan.state.unknown"));
            }
        }
        if (state != State.NORMAL && state != State.LINE_COMMENT) {
            throw new MalformedSqlException(MyBatisAssistantBundle.message(
                    "sqltool.log.error.sql.unclosed"));
        }
        finishStatement(visible, statements);
        return new ScanResult(rendered.toString(), replacementIndex, List.copyOf(statements));
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
        if (!tag.isEmpty() && !tag.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        return sql.substring(start, end + 1);
    }

    private static void appendSpaces(StringBuilder target, int count) {
        target.append(" ".repeat(count));
    }

    private static void finishStatement(StringBuilder visible, List<String> statements) {
        String text = visible.toString().trim();
        if (!text.isEmpty()) {
            statements.add(text);
        }
        visible.setLength(0);
    }

    enum State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        BRACKET,
        LINE_COMMENT,
        BLOCK_COMMENT,
        DOLLAR_QUOTE
    }

    record ScanResult(String renderedSql, int placeholderCount, List<String> statements) {
    }

    static final class MalformedSqlException extends Exception {
        MalformedSqlException(String message) {
            super(message);
        }
    }
}
