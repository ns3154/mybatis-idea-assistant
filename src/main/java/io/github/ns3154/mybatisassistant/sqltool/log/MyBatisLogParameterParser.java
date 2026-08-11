package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按 MyBatis BaseJdbcLogger 的 value(Type) 输出反向解析参数。
 */
final class MyBatisLogParameterParser {
    private static final int SEARCH_BUDGET = 20_000;
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$]*(?:\\[\\])?");
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][-+]?\\d+)?");
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "byte", "short", "integer", "int", "long", "float", "double",
            "bigdecimal", "biginteger", "atomicinteger", "atomiclong");
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "atomicboolean");

    private MyBatisLogParameterParser() {
    }

    static @NotNull ParseResult parse(@NotNull String source, int expectedCount) {
        if (expectedCount < 0) {
            throw new IllegalArgumentException("参数数量不能为负数");
        }
        if (expectedCount == 0) {
            return source.isBlank()
                    ? ParseResult.success(List.of())
                    : ParseResult.failure(MyBatisLogDiagnosticCode.PLACEHOLDER_COUNT_MISMATCH,
                            "SQL 不含 JDBC 占位符，但日志仍包含参数");
        }
        if (source.isEmpty()) {
            return ParseResult.failure(MyBatisLogDiagnosticCode.PLACEHOLDER_COUNT_MISMATCH,
                    "JDBC 占位符数量为 " + expectedCount + "，但参数日志为空");
        }
        List<List<ParameterLiteral>> solutions = new ArrayList<>(2);
        SearchState state = new SearchState(SEARCH_BUDGET);
        search(source, expectedCount, 0, new ArrayList<>(), solutions, state);
        if (solutions.size() > 1) {
            return ParseResult.failure(MyBatisLogDiagnosticCode.AMBIGUOUS_PARAMETERS,
                    "参数文本存在多种合法切分，已拒绝猜测");
        }
        if (solutions.isEmpty()) {
            MyBatisLogDiagnosticCode code = state.binarySeen
                    ? MyBatisLogDiagnosticCode.UNSUPPORTED_BINARY_PARAMETER
                    : state.invalidSeen
                    ? MyBatisLogDiagnosticCode.INVALID_PARAMETER
                    : MyBatisLogDiagnosticCode.PLACEHOLDER_COUNT_MISMATCH;
            String message = switch (code) {
                case UNSUPPORTED_BINARY_PARAMETER -> "二进制或流式参数无法从日志安全还原";
                case INVALID_PARAMETER -> "参数类型或值不符合可安全还原的 MyBatis 日志格式";
                default -> "JDBC 占位符与可解析参数数量不一致";
            };
            return ParseResult.failure(code, message);
        }
        return ParseResult.success(solutions.getFirst().stream()
                .map(ParameterLiteral::sqlLiteral)
                .toList());
    }

    private static void search(
            String source,
            int expectedCount,
            int start,
            List<ParameterLiteral> current,
            List<List<ParameterLiteral>> solutions,
            SearchState state) {
        if (solutions.size() > 1 || state.remaining-- <= 0) {
            return;
        }
        ProgressManager.checkCanceled();
        int remainingParameters = expectedCount - current.size();
        if (remainingParameters == 0) {
            if (start == source.length()) {
                solutions.add(List.copyOf(current));
            }
            return;
        }
        if (start >= source.length()) {
            return;
        }
        for (int end : boundaries(source, start)) {
            ProgressManager.checkCanceled();
            String candidate = source.substring(start, end);
            ParsedCandidate parsed = parseCandidate(candidate);
            if (parsed.binary) {
                state.binarySeen = true;
            } else if (parsed.literal == null) {
                state.invalidSeen = true;
            } else {
                current.add(parsed.literal);
                int next = end == source.length() ? end : end + 2;
                search(source, expectedCount, next, current, solutions, state);
                current.removeLast();
            }
            if (solutions.size() > 1) {
                return;
            }
        }
    }

    private static List<Integer> boundaries(String source, int start) {
        List<Integer> boundaries = new ArrayList<>();
        int cursor = start;
        while ((cursor = source.indexOf(", ", cursor)) >= 0) {
            boundaries.add(cursor);
            cursor += 2;
        }
        boundaries.add(source.length());
        return boundaries;
    }

    private static ParsedCandidate parseCandidate(String token) {
        if ("null".equals(token)) {
            return ParsedCandidate.literal("NULL");
        }
        if (!token.endsWith(")")) {
            return ParsedCandidate.invalid();
        }
        int opening = token.lastIndexOf('(');
        if (opening < 0) {
            return ParsedCandidate.invalid();
        }
        String type = token.substring(opening + 1, token.length() - 1);
        if (!TYPE_NAME.matcher(type).matches()) {
            return ParsedCandidate.invalid();
        }
        String value = token.substring(0, opening);
        String simpleType = simpleType(type).toLowerCase(Locale.ROOT);
        if (isBinary(simpleType)) {
            return ParsedCandidate.binaryCandidate();
        }
        if (NUMERIC_TYPES.contains(simpleType)) {
            return NUMBER.matcher(value).matches()
                    ? ParsedCandidate.literal(value) : ParsedCandidate.invalid();
        }
        if (BOOLEAN_TYPES.contains(simpleType)) {
            return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                    ? ParsedCandidate.literal(value.toUpperCase(Locale.ROOT))
                    : ParsedCandidate.invalid();
        }
        return ParsedCandidate.literal("'" + value.replace("'", "''") + "'");
    }

    private static String simpleType(String type) {
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
        return separator < 0 ? type : type.substring(separator + 1);
    }

    private static boolean isBinary(String type) {
        return type.equals("byte[]")
                || type.contains("blob")
                || type.contains("clob")
                || type.contains("stream")
                || type.contains("bytebuffer")
                || type.equals("reader");
    }

    private record ParameterLiteral(String sqlLiteral) {
    }

    private record ParsedCandidate(ParameterLiteral literal, boolean binary) {
        private static ParsedCandidate literal(String text) {
            return new ParsedCandidate(new ParameterLiteral(text), false);
        }

        private static ParsedCandidate invalid() {
            return new ParsedCandidate(null, false);
        }

        private static ParsedCandidate binaryCandidate() {
            return new ParsedCandidate(null, true);
        }
    }

    private static final class SearchState {
        private int remaining;
        private boolean binarySeen;
        private boolean invalidSeen;

        private SearchState(int remaining) {
            this.remaining = remaining;
        }
    }

    record ParseResult(
            boolean success,
            List<String> literals,
            MyBatisLogDiagnosticCode diagnosticCode,
            String message) {
        private static ParseResult success(List<String> literals) {
            return new ParseResult(true, List.copyOf(literals), null, "");
        }

        private static ParseResult failure(MyBatisLogDiagnosticCode code, String message) {
            return new ParseResult(false, List.of(), code, message);
        }
    }
}
