package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 支持错误恢复和深度上限的 OGNL Pratt 语法器。
 */
public final class MyBatisOgnlParser {
    private static final int MAXIMUM_NESTING = 256;

    private final List<MyBatisOgnlToken> tokens;
    private final List<MyBatisOgnlDiagnostic> diagnostics;
    private int cursor;
    private int nesting;

    private MyBatisOgnlParser(@NotNull MyBatisOgnlLexResult lexResult) {
        tokens = lexResult.tokens();
        diagnostics = new ArrayList<>(lexResult.diagnostics());
    }

    public static @NotNull MyBatisOgnlParseResult parse(@NotNull CharSequence source) {
        MyBatisOgnlParser parser = new MyBatisOgnlParser(MyBatisOgnlLexer.lex(source));
        MyBatisOgnlExpression root = parser.parseConditional();
        while (!parser.at(MyBatisOgnlTokenKind.EOF)) {
            ProgressManager.checkCanceled();
            MyBatisOgnlToken trailing = parser.advance();
            parser.diagnostic(
                    MyBatisOgnlDiagnosticCode.TRAILING_TOKEN,
                    "表达式结束后存在多余 token：" + trailing.text(),
                    trailing.range());
        }
        return new MyBatisOgnlParseResult(root, parser.tokens, parser.diagnostics);
    }

    private @NotNull MyBatisOgnlExpression parseConditional() {
        ProgressManager.checkCanceled();
        if (++nesting > MAXIMUM_NESTING) {
            MyBatisOgnlToken token = current();
            diagnostic(
                    MyBatisOgnlDiagnosticCode.MAXIMUM_NESTING_EXCEEDED,
                    "OGNL 嵌套深度超过 " + MAXIMUM_NESTING,
                    token.range());
            nesting--;
            if (!at(MyBatisOgnlTokenKind.EOF)) {
                advance();
            }
            return new MyBatisOgnlExpression.Error(token.range());
        }
        try {
            MyBatisOgnlExpression condition = parseBinary(1);
            if (!match(MyBatisOgnlTokenKind.QUESTION)) {
                return condition;
            }
            MyBatisOgnlExpression whenTrue = parseConditional();
            expect(MyBatisOgnlTokenKind.COLON, "三元表达式缺少冒号");
            MyBatisOgnlExpression whenFalse = parseConditional();
            return new MyBatisOgnlExpression.Conditional(
                    condition,
                    whenTrue,
                    whenFalse,
                    MyBatisOgnlRange.spanning(condition.range(), whenFalse.range()));
        } finally {
            nesting--;
        }
    }

    private @NotNull MyBatisOgnlExpression parseBinary(int minimumPrecedence) {
        MyBatisOgnlExpression left = parseUnary();
        while (true) {
            ProgressManager.checkCanceled();
            BinaryOperator operator = binaryOperator();
            if (operator == null || operator.precedence() < minimumPrecedence) {
                return left;
            }
            advance();
            if (operator.consumeSecondToken()) {
                advance();
            }
            MyBatisOgnlExpression right = operator.operator()
                    == MyBatisOgnlOperator.INSTANCE_OF
                    ? parseTypeLiteral()
                    : parseBinary(operator.precedence() + 1);
            left = new MyBatisOgnlExpression.Binary(
                    operator.operator(),
                    left,
                    right,
                    MyBatisOgnlRange.spanning(left.range(), right.range()));
        }
    }

    private @NotNull MyBatisOgnlExpression parseUnary() {
        List<UnaryPrefix> prefixes = new ArrayList<>();
        while (true) {
            MyBatisOgnlOperator operator = switch (current().kind()) {
                case PLUS -> MyBatisOgnlOperator.POSITIVE;
                case MINUS -> MyBatisOgnlOperator.NEGATIVE;
                case BANG, NOT -> MyBatisOgnlOperator.NOT;
                default -> null;
            };
            if (operator == null) {
                break;
            }
            prefixes.add(new UnaryPrefix(operator, advance().range()));
        }
        MyBatisOgnlExpression result = parsePostfix(parsePrimary());
        for (int index = prefixes.size() - 1; index >= 0; index--) {
            ProgressManager.checkCanceled();
            UnaryPrefix prefix = prefixes.get(index);
            result = new MyBatisOgnlExpression.Unary(
                    prefix.operator(),
                    result,
                    MyBatisOgnlRange.spanning(prefix.range(), result.range()));
        }
        return result;
    }

    private @NotNull MyBatisOgnlExpression parsePostfix(
            @NotNull MyBatisOgnlExpression initial) {
        MyBatisOgnlExpression result = initial;
        while (true) {
            ProgressManager.checkCanceled();
            if (match(MyBatisOgnlTokenKind.DOT)) {
                MyBatisOgnlToken name = expectIdentifier("点号后缺少属性或方法名");
                if (match(MyBatisOgnlTokenKind.LEFT_PAREN)) {
                    Arguments arguments = parseArguments();
                    result = new MyBatisOgnlExpression.MethodCall(
                            Optional.of(result),
                            name.text(),
                            name.range(),
                            arguments.values(),
                            new MyBatisOgnlRange(
                                    result.range().startOffset(),
                                    arguments.endOffset()));
                } else {
                    result = new MyBatisOgnlExpression.Property(
                            result,
                            name.text(),
                            name.range(),
                            MyBatisOgnlRange.spanning(result.range(), name.range()));
                }
                continue;
            }
            if (match(MyBatisOgnlTokenKind.LEFT_BRACKET)) {
                MyBatisOgnlExpression index = parseConditional();
                MyBatisOgnlToken close = expect(
                        MyBatisOgnlTokenKind.RIGHT_BRACKET,
                        "索引表达式缺少右方括号");
                result = new MyBatisOgnlExpression.Index(
                        result,
                        index,
                        new MyBatisOgnlRange(
                                result.range().startOffset(),
                                close.range().endOffset()));
                continue;
            }
            return result;
        }
    }

    private @NotNull MyBatisOgnlExpression parsePrimary() {
        ProgressManager.checkCanceled();
        MyBatisOgnlToken token = current();
        return switch (token.kind()) {
            case NULL, TRUE, FALSE, NUMBER, STRING -> literal(advance());
            case IDENTIFIER -> parseNameOrRootCall();
            case HASH -> parseHashExpression();
            case AT -> parseStaticMember();
            case LEFT_PAREN -> parseGroup();
            case LEFT_BRACE -> parseListLiteral();
            default -> errorExpression();
        };
    }

    private @NotNull MyBatisOgnlExpression parseNameOrRootCall() {
        MyBatisOgnlToken name = advance();
        if (!match(MyBatisOgnlTokenKind.LEFT_PAREN)) {
            return new MyBatisOgnlExpression.Name(
                    name.text(),
                    MyBatisOgnlNameKind.ROOT,
                    name.range());
        }
        Arguments arguments = parseArguments();
        return new MyBatisOgnlExpression.MethodCall(
                Optional.empty(),
                name.text(),
                name.range(),
                arguments.values(),
                new MyBatisOgnlRange(name.range().startOffset(), arguments.endOffset()));
    }

    private @NotNull MyBatisOgnlExpression parseHashExpression() {
        MyBatisOgnlToken hash = advance();
        if (match(MyBatisOgnlTokenKind.LEFT_BRACE)) {
            return parseMapLiteral(hash.range().startOffset());
        }
        MyBatisOgnlToken name = expectIdentifier("# 后缺少上下文变量名");
        return new MyBatisOgnlExpression.Name(
                name.text(),
                MyBatisOgnlNameKind.CONTEXT,
                new MyBatisOgnlRange(hash.range().startOffset(), name.range().endOffset()));
    }

    private @NotNull MyBatisOgnlExpression parseStaticMember() {
        MyBatisOgnlToken opening = advance();
        int classStart = current().range().startOffset();
        StringBuilder className = new StringBuilder();
        MyBatisOgnlToken lastClassToken = current();
        while (!at(MyBatisOgnlTokenKind.AT) && !at(MyBatisOgnlTokenKind.EOF)) {
            ProgressManager.checkCanceled();
            if (!at(MyBatisOgnlTokenKind.IDENTIFIER) && !at(MyBatisOgnlTokenKind.DOT)) {
                break;
            }
            lastClassToken = advance();
            className.append(lastClassToken.text());
        }
        if (className.isEmpty()) {
            diagnostic(
                    MyBatisOgnlDiagnosticCode.EXPECTED_IDENTIFIER,
                    "静态成员表达式缺少全限定类名",
                    current().range());
        }
        expect(MyBatisOgnlTokenKind.AT, "静态成员类名后缺少 @");
        MyBatisOgnlToken member = expectIdentifier("静态表达式缺少成员名");
        Optional<List<MyBatisOgnlExpression>> arguments = Optional.empty();
        int endOffset = member.range().endOffset();
        if (match(MyBatisOgnlTokenKind.LEFT_PAREN)) {
            Arguments parsed = parseArguments();
            arguments = Optional.of(parsed.values());
            endOffset = parsed.endOffset();
        }
        MyBatisOgnlRange classRange = new MyBatisOgnlRange(
                classStart,
                Math.max(classStart, lastClassToken.range().endOffset()));
        return new MyBatisOgnlExpression.StaticMember(
                className.toString(),
                classRange,
                member.text(),
                member.range(),
                arguments,
                new MyBatisOgnlRange(opening.range().startOffset(), endOffset));
    }

    private @NotNull MyBatisOgnlExpression parseTypeLiteral() {
        MyBatisOgnlToken first = expectIdentifier("instanceof 后缺少全限定类型名");
        StringBuilder qualifiedName = new StringBuilder(first.text());
        int endOffset = first.range().endOffset();
        while (match(MyBatisOgnlTokenKind.DOT)) {
            MyBatisOgnlToken part = expectIdentifier("类型限定名的点号后缺少名称");
            qualifiedName.append('.').append(part.text());
            endOffset = part.range().endOffset();
        }
        MyBatisOgnlRange range = new MyBatisOgnlRange(
                first.range().startOffset(),
                endOffset);
        return new MyBatisOgnlExpression.TypeLiteral(
                qualifiedName.toString(),
                range,
                range);
    }

    private @NotNull MyBatisOgnlExpression parseGroup() {
        MyBatisOgnlToken opening = advance();
        MyBatisOgnlExpression expression = parseConditional();
        MyBatisOgnlToken close = expect(
                MyBatisOgnlTokenKind.RIGHT_PAREN,
                "分组表达式缺少右括号");
        return new MyBatisOgnlExpression.Group(
                expression,
                new MyBatisOgnlRange(
                        opening.range().startOffset(),
                        close.range().endOffset()));
    }

    private @NotNull MyBatisOgnlExpression parseListLiteral() {
        MyBatisOgnlToken opening = advance();
        List<MyBatisOgnlExpression> elements = parseDelimitedExpressions(
                MyBatisOgnlTokenKind.RIGHT_BRACE);
        MyBatisOgnlToken close = expect(
                MyBatisOgnlTokenKind.RIGHT_BRACE,
                "列表字面量缺少右花括号");
        return new MyBatisOgnlExpression.ListLiteral(
                elements,
                new MyBatisOgnlRange(
                        opening.range().startOffset(),
                        close.range().endOffset()));
    }

    private @NotNull MyBatisOgnlExpression parseMapLiteral(int startOffset) {
        List<MyBatisOgnlExpression.MapEntry> entries = new ArrayList<>();
        while (!at(MyBatisOgnlTokenKind.RIGHT_BRACE)
                && !at(MyBatisOgnlTokenKind.EOF)) {
            ProgressManager.checkCanceled();
            MyBatisOgnlExpression key = parseConditional();
            expect(MyBatisOgnlTokenKind.COLON, "Map 条目缺少冒号");
            MyBatisOgnlExpression value = parseConditional();
            entries.add(new MyBatisOgnlExpression.MapEntry(
                    key,
                    value,
                    MyBatisOgnlRange.spanning(key.range(), value.range())));
            if (!match(MyBatisOgnlTokenKind.COMMA)) {
                break;
            }
        }
        MyBatisOgnlToken close = expect(
                MyBatisOgnlTokenKind.RIGHT_BRACE,
                "Map 字面量缺少右花括号");
        return new MyBatisOgnlExpression.MapLiteral(
                entries,
                new MyBatisOgnlRange(startOffset, close.range().endOffset()));
    }

    private @NotNull Arguments parseArguments() {
        List<MyBatisOgnlExpression> arguments = parseDelimitedExpressions(
                MyBatisOgnlTokenKind.RIGHT_PAREN);
        MyBatisOgnlToken close = expect(
                MyBatisOgnlTokenKind.RIGHT_PAREN,
                "方法调用缺少右括号");
        return new Arguments(arguments, close.range().endOffset());
    }

    private @NotNull List<MyBatisOgnlExpression> parseDelimitedExpressions(
            @NotNull MyBatisOgnlTokenKind closingKind) {
        List<MyBatisOgnlExpression> expressions = new ArrayList<>();
        if (at(closingKind)) {
            return expressions;
        }
        while (!at(closingKind) && !at(MyBatisOgnlTokenKind.EOF)) {
            ProgressManager.checkCanceled();
            expressions.add(parseConditional());
            if (!match(MyBatisOgnlTokenKind.COMMA)) {
                break;
            }
        }
        return expressions;
    }

    private @NotNull MyBatisOgnlExpression literal(@NotNull MyBatisOgnlToken token) {
        return switch (token.kind()) {
            case NULL -> new MyBatisOgnlExpression.Literal(
                    token.text(), "", MyBatisOgnlLiteralKind.NULL, token.range());
            case TRUE, FALSE -> new MyBatisOgnlExpression.Literal(
                    token.text(),
                    token.text().toLowerCase(java.util.Locale.ROOT),
                    MyBatisOgnlLiteralKind.BOOLEAN,
                    token.range());
            case NUMBER -> numberLiteral(token);
            case STRING -> stringLiteral(token);
            default -> throw new IllegalArgumentException("不是字面量 token：" + token.kind());
        };
    }

    private @NotNull MyBatisOgnlExpression numberLiteral(@NotNull MyBatisOgnlToken token) {
        String normalized = token.text().replace("_", "");
        boolean decimal = normalized.indexOf('.') >= 0
                || normalized.indexOf('e') >= 0
                || normalized.indexOf('E') >= 0
                || normalized.endsWith("f")
                || normalized.endsWith("F")
                || normalized.endsWith("d")
                || normalized.endsWith("D");
        try {
            if (decimal) {
                String value = normalized.replaceFirst("[fFdD]$", "");
                Double.parseDouble(value);
            } else {
                String value = normalized.replaceFirst("[lL]$", "");
                if (value.startsWith("0x") || value.startsWith("0X")) {
                    Long.parseUnsignedLong(value.substring(2), 16);
                } else {
                    Long.parseLong(value);
                }
            }
        } catch (NumberFormatException invalid) {
            diagnostic(
                    MyBatisOgnlDiagnosticCode.INVALID_NUMBER,
                    "无效数字字面量：" + token.text(),
                    token.range());
        }
        return new MyBatisOgnlExpression.Literal(
                token.text(),
                normalized,
                decimal ? MyBatisOgnlLiteralKind.DECIMAL : MyBatisOgnlLiteralKind.INTEGER,
                token.range());
    }

    private @NotNull MyBatisOgnlExpression stringLiteral(@NotNull MyBatisOgnlToken token) {
        String raw = token.text();
        boolean closed = raw.length() >= 2 && raw.charAt(0) == raw.charAt(raw.length() - 1);
        String content = raw.substring(1, closed ? raw.length() - 1 : raw.length());
        String value = decodeString(content, token.range().startOffset() + 1);
        MyBatisOgnlLiteralKind kind = raw.startsWith("'")
                && value.codePointCount(0, value.length()) == 1
                ? MyBatisOgnlLiteralKind.CHARACTER
                : MyBatisOgnlLiteralKind.STRING;
        return new MyBatisOgnlExpression.Literal(raw, value, kind, token.range());
    }

    private @NotNull String decodeString(@NotNull String content, int sourceOffset) {
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= content.length()) {
                diagnostic(
                        MyBatisOgnlDiagnosticCode.INVALID_ESCAPE,
                        "字符串末尾存在不完整转义",
                        new MyBatisOgnlRange(sourceOffset + index - 1, sourceOffset + index));
                break;
            }
            char escaped = content.charAt(index);
            switch (escaped) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case '\\' -> decoded.append('\\');
                case '\'' -> decoded.append('\'');
                case '"' -> decoded.append('"');
                default -> {
                    diagnostic(
                            MyBatisOgnlDiagnosticCode.INVALID_ESCAPE,
                            "不支持的字符串转义：\\" + escaped,
                            new MyBatisOgnlRange(
                                    sourceOffset + index - 1,
                                    sourceOffset + index + 1));
                    decoded.append(escaped);
                }
            }
        }
        return decoded.toString();
    }

    private @NotNull MyBatisOgnlExpression errorExpression() {
        MyBatisOgnlToken token = current();
        diagnostic(
                MyBatisOgnlDiagnosticCode.EXPECTED_EXPRESSION,
                "此处需要 OGNL 表达式",
                token.range());
        if (!at(MyBatisOgnlTokenKind.EOF)) {
            advance();
        }
        return new MyBatisOgnlExpression.Error(token.range());
    }

    private @Nullable BinaryOperator binaryOperator() {
        MyBatisOgnlTokenKind kind = current().kind();
        if (kind == MyBatisOgnlTokenKind.NOT
                && lookAhead(1).kind() == MyBatisOgnlTokenKind.IN) {
            return new BinaryOperator(MyBatisOgnlOperator.NOT_IN, 4, true);
        }
        return switch (kind) {
            case OR, OR_OR -> new BinaryOperator(MyBatisOgnlOperator.OR, 1, false);
            case AND, AND_AND -> new BinaryOperator(MyBatisOgnlOperator.AND, 2, false);
            case EQ, EQ_EQ -> new BinaryOperator(MyBatisOgnlOperator.EQUAL, 3, false);
            case NEQ, NOT_EQ -> new BinaryOperator(MyBatisOgnlOperator.NOT_EQUAL, 3, false);
            case LESS, LT -> new BinaryOperator(MyBatisOgnlOperator.LESS, 4, false);
            case LESS_EQ, LTE -> new BinaryOperator(MyBatisOgnlOperator.LESS_OR_EQUAL, 4, false);
            case GREATER, GT -> new BinaryOperator(MyBatisOgnlOperator.GREATER, 4, false);
            case GREATER_EQ, GTE -> new BinaryOperator(
                    MyBatisOgnlOperator.GREATER_OR_EQUAL, 4, false);
            case IN -> new BinaryOperator(MyBatisOgnlOperator.IN, 4, false);
            case INSTANCEOF -> new BinaryOperator(MyBatisOgnlOperator.INSTANCE_OF, 4, false);
            case PLUS -> new BinaryOperator(MyBatisOgnlOperator.ADD, 5, false);
            case MINUS -> new BinaryOperator(MyBatisOgnlOperator.SUBTRACT, 5, false);
            case STAR -> new BinaryOperator(MyBatisOgnlOperator.MULTIPLY, 6, false);
            case SLASH -> new BinaryOperator(MyBatisOgnlOperator.DIVIDE, 6, false);
            case PERCENT -> new BinaryOperator(MyBatisOgnlOperator.REMAINDER, 6, false);
            default -> null;
        };
    }

    private boolean match(@NotNull MyBatisOgnlTokenKind kind) {
        if (!at(kind)) {
            return false;
        }
        advance();
        return true;
    }

    private @NotNull MyBatisOgnlToken expect(
            @NotNull MyBatisOgnlTokenKind kind,
            @NotNull String message) {
        if (at(kind)) {
            return advance();
        }
        diagnostic(MyBatisOgnlDiagnosticCode.EXPECTED_TOKEN, message, current().range());
        return new MyBatisOgnlToken(kind, "", current().range());
    }

    private @NotNull MyBatisOgnlToken expectIdentifier(@NotNull String message) {
        if (at(MyBatisOgnlTokenKind.IDENTIFIER)) {
            return advance();
        }
        diagnostic(
                MyBatisOgnlDiagnosticCode.EXPECTED_IDENTIFIER,
                message,
                current().range());
        return new MyBatisOgnlToken(
                MyBatisOgnlTokenKind.IDENTIFIER,
                "",
                current().range());
    }

    private boolean at(@NotNull MyBatisOgnlTokenKind kind) {
        return current().kind() == kind;
    }

    private @NotNull MyBatisOgnlToken current() {
        return tokens.get(Math.min(cursor, tokens.size() - 1));
    }

    private @NotNull MyBatisOgnlToken lookAhead(int distance) {
        return tokens.get(Math.min(cursor + distance, tokens.size() - 1));
    }

    private @NotNull MyBatisOgnlToken advance() {
        ProgressManager.checkCanceled();
        MyBatisOgnlToken token = current();
        if (cursor < tokens.size() - 1) {
            cursor++;
        }
        return token;
    }

    private void diagnostic(
            @NotNull MyBatisOgnlDiagnosticCode code,
            @NotNull String message,
            @NotNull MyBatisOgnlRange range) {
        diagnostics.add(new MyBatisOgnlDiagnostic(code, message, range));
    }

    private record BinaryOperator(
            @NotNull MyBatisOgnlOperator operator,
            int precedence,
            boolean consumeSecondToken) {
    }

    private record UnaryPrefix(
            @NotNull MyBatisOgnlOperator operator,
            @NotNull MyBatisOgnlRange range) {
    }

    private record Arguments(
            @NotNull List<MyBatisOgnlExpression> values,
            int endOffset) {
        private Arguments {
            values = List.copyOf(values);
        }
    }
}
