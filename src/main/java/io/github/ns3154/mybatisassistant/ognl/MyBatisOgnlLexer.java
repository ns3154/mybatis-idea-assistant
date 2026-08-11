package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 线性、可取消且不抛用户输入异常的 OGNL 词法器。
 */
public final class MyBatisOgnlLexer {
    private static final Map<String, MyBatisOgnlTokenKind> KEYWORDS = Map.ofEntries(
            Map.entry("null", MyBatisOgnlTokenKind.NULL),
            Map.entry("true", MyBatisOgnlTokenKind.TRUE),
            Map.entry("false", MyBatisOgnlTokenKind.FALSE),
            Map.entry("and", MyBatisOgnlTokenKind.AND),
            Map.entry("or", MyBatisOgnlTokenKind.OR),
            Map.entry("not", MyBatisOgnlTokenKind.NOT),
            Map.entry("eq", MyBatisOgnlTokenKind.EQ),
            Map.entry("neq", MyBatisOgnlTokenKind.NEQ),
            Map.entry("lt", MyBatisOgnlTokenKind.LT),
            Map.entry("lte", MyBatisOgnlTokenKind.LTE),
            Map.entry("gt", MyBatisOgnlTokenKind.GT),
            Map.entry("gte", MyBatisOgnlTokenKind.GTE),
            Map.entry("in", MyBatisOgnlTokenKind.IN),
            Map.entry("instanceof", MyBatisOgnlTokenKind.INSTANCEOF),
            Map.entry("new", MyBatisOgnlTokenKind.NEW));

    private MyBatisOgnlLexer() {
    }

    public static @NotNull MyBatisOgnlLexResult lex(@NotNull CharSequence source) {
        List<MyBatisOgnlToken> tokens = new ArrayList<>();
        List<MyBatisOgnlDiagnostic> diagnostics = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            ProgressManager.checkCanceled();
            char current = source.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            int start = cursor;
            if (Character.isJavaIdentifierStart(current)) {
                cursor = identifierEnd(source, cursor + 1);
                String text = source.subSequence(start, cursor).toString();
                tokens.add(token(KEYWORDS.getOrDefault(
                        text.toLowerCase(java.util.Locale.ROOT),
                        MyBatisOgnlTokenKind.IDENTIFIER), text, start, cursor));
                continue;
            }
            if (Character.isDigit(current)) {
                cursor = numberEnd(source, cursor);
                String text = source.subSequence(start, cursor).toString();
                tokens.add(token(MyBatisOgnlTokenKind.NUMBER, text, start, cursor));
                continue;
            }
            if (current == '\'' || current == '"') {
                cursor = stringEnd(source, cursor, diagnostics);
                tokens.add(token(
                        MyBatisOgnlTokenKind.STRING,
                        source.subSequence(start, cursor).toString(),
                        start,
                        cursor));
                continue;
            }
            MyBatisOgnlTokenKind doubleKind = cursor + 1 < source.length()
                    ? doubleToken(current, source.charAt(cursor + 1))
                    : null;
            if (doubleKind != null) {
                cursor += 2;
                tokens.add(token(doubleKind, source.subSequence(start, cursor).toString(),
                        start, cursor));
                continue;
            }
            MyBatisOgnlTokenKind singleKind = singleToken(current);
            cursor++;
            if (singleKind == null) {
                MyBatisOgnlRange range = new MyBatisOgnlRange(start, cursor);
                diagnostics.add(new MyBatisOgnlDiagnostic(
                        MyBatisOgnlDiagnosticCode.UNEXPECTED_CHARACTER,
                        "无法识别的 OGNL 字符：" + current,
                        range));
                tokens.add(new MyBatisOgnlToken(
                        MyBatisOgnlTokenKind.BAD_CHARACTER,
                        Character.toString(current),
                        range));
            } else {
                tokens.add(token(singleKind, Character.toString(current), start, cursor));
            }
        }
        tokens.add(token(MyBatisOgnlTokenKind.EOF, "", source.length(), source.length()));
        return new MyBatisOgnlLexResult(tokens, diagnostics);
    }

    private static int identifierEnd(CharSequence source, int cursor) {
        while (cursor < source.length()
                && Character.isJavaIdentifierPart(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int numberEnd(CharSequence source, int cursor) {
        boolean exponentSignAllowed = false;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (Character.isDigit(current)
                    || current == '_'
                    || current == '.'
                    || current == 'x'
                    || current == 'X'
                    || current == 'e'
                    || current == 'E'
                    || current == 'l'
                    || current == 'L'
                    || current == 'f'
                    || current == 'F'
                    || current == 'd'
                    || current == 'D') {
                exponentSignAllowed = current == 'e' || current == 'E';
                cursor++;
            } else if ((current == '+' || current == '-') && exponentSignAllowed) {
                exponentSignAllowed = false;
                cursor++;
            } else {
                break;
            }
        }
        return cursor;
    }

    private static int stringEnd(
            CharSequence source,
            int quoteOffset,
            List<MyBatisOgnlDiagnostic> diagnostics) {
        char quote = source.charAt(quoteOffset);
        int cursor = quoteOffset + 1;
        boolean escaped = false;
        while (cursor < source.length()) {
            char current = source.charAt(cursor++);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == quote) {
                return cursor;
            }
        }
        diagnostics.add(new MyBatisOgnlDiagnostic(
                MyBatisOgnlDiagnosticCode.UNTERMINATED_STRING,
                "OGNL 字符串缺少结束引号",
                new MyBatisOgnlRange(quoteOffset, source.length())));
        return source.length();
    }

    private static MyBatisOgnlTokenKind doubleToken(char first, char second) {
        return switch ("" + first + second) {
            case "&&" -> MyBatisOgnlTokenKind.AND_AND;
            case "||" -> MyBatisOgnlTokenKind.OR_OR;
            case "==" -> MyBatisOgnlTokenKind.EQ_EQ;
            case "!=" -> MyBatisOgnlTokenKind.NOT_EQ;
            case "<=" -> MyBatisOgnlTokenKind.LESS_EQ;
            case ">=" -> MyBatisOgnlTokenKind.GREATER_EQ;
            default -> null;
        };
    }

    private static MyBatisOgnlTokenKind singleToken(char value) {
        return switch (value) {
            case '+' -> MyBatisOgnlTokenKind.PLUS;
            case '-' -> MyBatisOgnlTokenKind.MINUS;
            case '*' -> MyBatisOgnlTokenKind.STAR;
            case '/' -> MyBatisOgnlTokenKind.SLASH;
            case '%' -> MyBatisOgnlTokenKind.PERCENT;
            case '!' -> MyBatisOgnlTokenKind.BANG;
            case '<' -> MyBatisOgnlTokenKind.LESS;
            case '>' -> MyBatisOgnlTokenKind.GREATER;
            case '?' -> MyBatisOgnlTokenKind.QUESTION;
            case ':' -> MyBatisOgnlTokenKind.COLON;
            case '.' -> MyBatisOgnlTokenKind.DOT;
            case ',' -> MyBatisOgnlTokenKind.COMMA;
            case '(' -> MyBatisOgnlTokenKind.LEFT_PAREN;
            case ')' -> MyBatisOgnlTokenKind.RIGHT_PAREN;
            case '[' -> MyBatisOgnlTokenKind.LEFT_BRACKET;
            case ']' -> MyBatisOgnlTokenKind.RIGHT_BRACKET;
            case '{' -> MyBatisOgnlTokenKind.LEFT_BRACE;
            case '}' -> MyBatisOgnlTokenKind.RIGHT_BRACE;
            case '@' -> MyBatisOgnlTokenKind.AT;
            case '#' -> MyBatisOgnlTokenKind.HASH;
            default -> null;
        };
    }

    private static MyBatisOgnlToken token(
            MyBatisOgnlTokenKind kind,
            String text,
            int start,
            int end) {
        return new MyBatisOgnlToken(kind, text, new MyBatisOgnlRange(start, end));
    }
}
