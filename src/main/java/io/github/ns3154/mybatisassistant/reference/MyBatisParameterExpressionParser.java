package io.github.ns3154.mybatisassistant.reference;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 只提取 S4 支持的参数点路径；完整 OGNL 表达式由后续阶段处理。
 */
final class MyBatisParameterExpressionParser {
    private MyBatisParameterExpressionParser() {
    }

    static @NotNull List<ParameterPath> parsePlaceholders(@NotNull String text) {
        List<ParameterPath> paths = new ArrayList<>();
        int cursor = 0;
        while (cursor + 1 < text.length()) {
            ProgressManager.checkCanceled();
            char marker = text.charAt(cursor);
            if ((marker != '#' && marker != '$') || text.charAt(cursor + 1) != '{') {
                cursor++;
                continue;
            }
            int contentStart = cursor + 2;
            int closingBrace = text.indexOf('}', contentStart);
            int contentEnd = closingBrace < 0 ? text.length() : closingBrace;
            int pathEnd = marker == '#'
                    ? firstComma(text, contentStart, contentEnd)
                    : contentEnd;
            ParameterPath path = parsePath(text, contentStart, pathEnd);
            if (!path.segments().isEmpty()) {
                paths.add(path);
            }
            cursor = closingBrace < 0 ? text.length() : closingBrace + 1;
        }
        return List.copyOf(paths);
    }

    static @NotNull List<ParameterPath> parseCommaSeparatedPaths(@NotNull String text) {
        List<ParameterPath> paths = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            ProgressManager.checkCanceled();
            int comma = text.indexOf(',', start);
            int end = comma < 0 ? text.length() : comma;
            ParameterPath path = parsePath(text, start, end);
            if (!path.segments().isEmpty()) {
                paths.add(path);
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return List.copyOf(paths);
    }

    private static int firstComma(@NotNull String text, int start, int end) {
        int comma = text.indexOf(',', start);
        return comma < 0 || comma > end ? end : comma;
    }

    private static @NotNull ParameterPath parsePath(
            @NotNull String text,
            int rawStart,
            int rawEnd) {
        int cursor = rawStart;
        while (cursor < rawEnd && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        List<PathSegment> segments = new ArrayList<>();
        while (cursor < rawEnd) {
            ProgressManager.checkCanceled();
            int nameStart = cursor;
            if (!isIdentifierStart(text.charAt(cursor))) {
                break;
            }
            cursor++;
            while (cursor < rawEnd && isIdentifierPart(text.charAt(cursor))) {
                cursor++;
            }
            int nameEnd = cursor;
            int indexDepth = 0;
            boolean dynamicIndex = false;
            while (cursor < rawEnd && text.charAt(cursor) == '[') {
                int closingBracket = text.indexOf(']', cursor + 1);
                if (closingBracket < 0 || closingBracket >= rawEnd) {
                    dynamicIndex = true;
                    cursor = rawEnd;
                    break;
                }
                String index = text.substring(cursor + 1, closingBracket).trim();
                if (index.isEmpty() || index.chars().anyMatch(character -> !Character.isDigit(character))) {
                    dynamicIndex = true;
                }
                indexDepth++;
                cursor = closingBracket + 1;
            }
            segments.add(new PathSegment(
                    text.substring(nameStart, nameEnd),
                    TextRange.create(nameStart, nameEnd),
                    indexDepth,
                    dynamicIndex));
            if (cursor >= rawEnd || text.charAt(cursor) != '.') {
                break;
            }
            cursor++;
        }
        return new ParameterPath(segments);
    }

    private static boolean isIdentifierStart(char character) {
        return Character.isJavaIdentifierStart(character) || character == '$';
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isJavaIdentifierPart(character) || character == '$';
    }

    record ParameterPath(@NotNull List<PathSegment> segments) {
        ParameterPath {
            segments = List.copyOf(segments);
        }
    }

    record PathSegment(
            @NotNull String name,
            @NotNull TextRange range,
            int indexDepth,
            boolean dynamicIndex) {
    }
}
