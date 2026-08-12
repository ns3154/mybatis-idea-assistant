package io.github.ns3154.mybatisassistant.sql;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisBindNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisChooseNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlProgram;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisForeachNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisIfNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisIncludeNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisMappedText;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapBuilder;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapSegment;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapping;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceRange;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSqlSequenceNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSqlTextNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTrimKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTrimNode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把 S5 符号程序压成单条线性代表 SQL，不执行动态表达式。
 */
public final class MyBatisVirtualSqlBuilder {
    private static final String PARAMETER_MARKER = "?";
    private static final String DYNAMIC_IDENTIFIER = "__mybatis_dynamic__";

    private MyBatisVirtualSqlBuilder() {
    }

    public static @NotNull MyBatisVirtualSql build(@NotNull MyBatisDynamicSqlProgram program) {
        BuildContext context = new BuildContext();
        MyBatisMappedText mappedText = context.render(program.root());
        return new MyBatisVirtualSql(
                mappedText,
                context.diagnostics,
                context.representativeOnly);
    }

    private static void appendNode(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisDynamicSqlNode node,
            @NotNull BuildContext context) {
        ProgressManager.checkCanceled();
        if (node instanceof MyBatisSqlTextNode textNode) {
            appendNormalizedSql(output, textNode.content(), context);
        } else if (node instanceof MyBatisSqlSequenceNode sequenceNode) {
            for (MyBatisDynamicSqlNode child : sequenceNode.children()) {
                appendNode(output, child, context);
            }
        } else if (node instanceof MyBatisIfNode ifNode) {
            context.representativeOnly = true;
            appendNode(output, ifNode.body(), context);
        } else if (node instanceof MyBatisChooseNode chooseNode) {
            appendChoose(output, chooseNode, context);
        } else if (node instanceof MyBatisTrimNode trimNode) {
            appendTrim(output, trimNode, context);
        } else if (node instanceof MyBatisForeachNode foreachNode) {
            appendForeach(output, foreachNode, context);
        } else if (node instanceof MyBatisIncludeNode includeNode) {
            appendNode(output, includeNode.expandedBody(), context);
        } else if (node instanceof MyBatisBindNode) {
            context.representativeOnly = true;
        }
    }

    private static void appendChoose(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisChooseNode chooseNode,
            @NotNull BuildContext context) {
        context.representativeOnly = true;
        int possibleBranches = chooseNode.branches().size()
                + (chooseNode.otherwiseBranch().isPresent() ? 1 : 0);
        if (possibleBranches > 1) {
            context.diagnostics.add(new MyBatisVirtualSqlDiagnostic(
                    MyBatisVirtualSqlDiagnosticCode.CHOOSE_BRANCHES_COLLAPSED,
                    MyBatisAssistantBundle.message(
                            "sql.virtual.diagnostic.choose.collapsed"),
                    chooseNode.sourceRange()));
        }
        if (!chooseNode.branches().isEmpty()) {
            appendNode(output, chooseNode.branches().getFirst().body(), context);
        } else {
            chooseNode.otherwiseBranch().ifPresent(branch -> appendNode(output, branch, context));
        }
    }

    private static void appendTrim(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisTrimNode trimNode,
            @NotNull BuildContext context) {
        MyBatisMappedText body = context.render(trimNode.body());
        String text = body.text();
        int start = skipWhitespaceForward(text, 0);
        int end = skipWhitespaceBackward(text, text.length());
        start = removePrefixOverride(text, start, end, effectivePrefixOverrides(trimNode));
        end = removeSuffixOverride(text, start, end, trimNode.suffixOverrides());
        if (start >= end) {
            return;
        }
        String prefix = effectivePrefix(trimNode);
        if (!prefix.isBlank()) {
            appendSynthetic(output, prefix + " ", trimNode.sourceRange());
        }
        appendMappedSlice(output, body, start, end);
        if (!trimNode.suffix().isBlank()) {
            appendSynthetic(output, " " + trimNode.suffix(), trimNode.sourceRange());
        }
    }

    private static void appendForeach(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisForeachNode foreachNode,
            @NotNull BuildContext context) {
        context.representativeOnly = true;
        if (!foreachNode.open().isEmpty()) {
            appendSynthetic(output, foreachNode.open(), foreachNode.sourceRange());
        }
        appendNode(output, foreachNode.body(), context);
        if (!foreachNode.close().isEmpty()) {
            appendSynthetic(output, foreachNode.close(), foreachNode.sourceRange());
        }
    }

    private static void appendNormalizedSql(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisMappedText mappedText,
            @NotNull BuildContext context) {
        String text = mappedText.text();
        int cursor = 0;
        while (cursor < text.length()) {
            ProgressManager.checkCanceled();
            int marker = findPlaceholderStart(text, cursor);
            if (marker < 0) {
                appendMappedSlice(output, mappedText, cursor, text.length());
                return;
            }
            appendMappedSlice(output, mappedText, cursor, marker);
            int close = text.indexOf('}', marker + 2);
            if (close < 0) {
                MyBatisSourceRange source = sourceRange(mappedText, marker, text.length());
                // 保留原文但标为 DECODED，使 SQL 注入跳过未闭合占位符范围，
                // 原 XML 的参数引用与补全仍能在用户编辑期间接管该文本。
                appendDecoded(output, text.substring(marker), source);
                context.diagnostics.add(new MyBatisVirtualSqlDiagnostic(
                        MyBatisVirtualSqlDiagnosticCode.MALFORMED_PARAMETER_PLACEHOLDER,
                        MyBatisAssistantBundle.message(
                                "sql.virtual.diagnostic.parameter.brace.missing"),
                        source));
                return;
            }
            String replacement = text.charAt(marker) == '#'
                    ? PARAMETER_MARKER
                    : DYNAMIC_IDENTIFIER;
            appendDecoded(output, replacement, sourceRange(mappedText, marker, close + 1));
            cursor = close + 1;
        }
    }

    private static int findPlaceholderStart(@NotNull String text, int fromIndex) {
        for (int index = fromIndex; index + 1 < text.length(); index++) {
            char first = text.charAt(index);
            if ((first == '#' || first == '$') && text.charAt(index + 1) == '{') {
                return index;
            }
        }
        return -1;
    }

    private static @NotNull MyBatisSourceRange sourceRange(
            @NotNull MyBatisMappedText mappedText,
            int start,
            int end) {
        List<MyBatisSourceMapping> mappings = mappedText.sourceMap()
                .sourceMappings(new MyBatisTextRange(start, end));
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sql.virtual.error.parameter.source.missing"));
        }
        MyBatisSourceRange first = mappings.getFirst().sourceRange();
        int sourceStart = first.range().startOffset();
        int sourceEnd = first.range().endOffset();
        for (MyBatisSourceMapping mapping : mappings) {
            MyBatisSourceRange candidate = mapping.sourceRange();
            if (!candidate.fileUrl().equals(first.fileUrl())) {
                return first;
            }
            sourceStart = Math.min(sourceStart, candidate.range().startOffset());
            sourceEnd = Math.max(sourceEnd, candidate.range().endOffset());
        }
        return new MyBatisSourceRange(
                first.fileUrl(),
                new MyBatisTextRange(sourceStart, sourceEnd));
    }

    private static void appendMappedSlice(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull MyBatisMappedText mappedText,
            int start,
            int end) {
        if (start >= end) {
            return;
        }
        MyBatisTextRange requested = new MyBatisTextRange(start, end);
        for (MyBatisSourceMapSegment segment : mappedText.sourceMap().segments()) {
            ProgressManager.checkCanceled();
            segment.virtualRange().intersection(requested).ifPresent(intersection -> {
                String value = mappedText.text().substring(
                        intersection.startOffset(),
                        intersection.endOffset());
                MyBatisSourceRange source = segment.sourceRange();
                if (segment.kind() == MyBatisSourceMapKind.EXACT) {
                    int delta = intersection.startOffset()
                            - segment.virtualRange().startOffset();
                    output.appendExact(
                            value,
                            source.fileUrl(),
                            source.range().startOffset() + delta);
                } else if (segment.kind() == MyBatisSourceMapKind.DECODED) {
                    output.appendDecoded(value, source.fileUrl(), source.range());
                } else {
                    output.appendSynthetic(value, source.fileUrl(), source.range());
                }
            });
        }
    }

    private static void appendDecoded(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull String value,
            @NotNull MyBatisSourceRange source) {
        output.appendDecoded(value, source.fileUrl(), source.range());
    }

    private static void appendSynthetic(
            @NotNull MyBatisSourceMapBuilder output,
            @NotNull String value,
            @NotNull MyBatisSourceRange source) {
        output.appendSynthetic(value, source.fileUrl(), source.range());
    }

    private static int skipWhitespaceForward(@NotNull String value, int start) {
        int result = start;
        while (result < value.length() && Character.isWhitespace(value.charAt(result))) {
            result++;
        }
        return result;
    }

    private static int skipWhitespaceBackward(@NotNull String value, int end) {
        int result = end;
        while (result > 0 && Character.isWhitespace(value.charAt(result - 1))) {
            result--;
        }
        return result;
    }

    private static int removePrefixOverride(
            @NotNull String text,
            int start,
            int end,
            @NotNull String overrides) {
        String upper = text.substring(start, end).toUpperCase(Locale.ROOT);
        for (String candidate : splitOverrides(overrides)) {
            if (upper.startsWith(candidate.toUpperCase(Locale.ROOT))) {
                return skipWhitespaceForward(text, start + candidate.length());
            }
        }
        return start;
    }

    private static int removeSuffixOverride(
            @NotNull String text,
            int start,
            int end,
            @NotNull String overrides) {
        String upper = text.substring(start, end).toUpperCase(Locale.ROOT);
        for (String candidate : splitOverrides(overrides)) {
            if (upper.endsWith(candidate.toUpperCase(Locale.ROOT))) {
                return skipWhitespaceBackward(text, end - candidate.length());
            }
        }
        return end;
    }

    private static @NotNull List<String> splitOverrides(@NotNull String overrides) {
        if (overrides.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String candidate : overrides.split("\\|")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static @NotNull String effectivePrefix(@NotNull MyBatisTrimNode node) {
        if (node.kind() == MyBatisTrimKind.WHERE) {
            return "WHERE";
        }
        if (node.kind() == MyBatisTrimKind.SET) {
            return "SET";
        }
        return node.prefix();
    }

    private static @NotNull String effectivePrefixOverrides(@NotNull MyBatisTrimNode node) {
        if (node.kind() == MyBatisTrimKind.WHERE && node.prefixOverrides().isBlank()) {
            return "AND|OR";
        }
        return node.prefixOverrides();
    }

    private static final class BuildContext {
        private final List<MyBatisVirtualSqlDiagnostic> diagnostics = new ArrayList<>();
        private boolean representativeOnly;

        private @NotNull MyBatisMappedText render(@NotNull MyBatisDynamicSqlNode node) {
            MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
            appendNode(builder, node, this);
            return builder.build();
        }
    }
}
