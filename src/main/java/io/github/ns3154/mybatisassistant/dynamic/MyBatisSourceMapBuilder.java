package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 以压缩映射段构建虚拟 SQL。
 */
public final class MyBatisSourceMapBuilder {
    private final StringBuilder text = new StringBuilder();
    private final List<MyBatisSourceMapSegment> segments = new ArrayList<>();

    public void appendExact(
            @NotNull String value,
            @NotNull String fileUrl,
            int sourceStartOffset) {
        if (value.isEmpty()) {
            return;
        }
        append(value, new MyBatisSourceRange(
                fileUrl,
                new MyBatisTextRange(sourceStartOffset, sourceStartOffset + value.length())),
                MyBatisSourceMapKind.EXACT);
    }

    public void appendDecoded(
            @NotNull String value,
            @NotNull String fileUrl,
            @NotNull MyBatisTextRange sourceRange) {
        append(value, new MyBatisSourceRange(fileUrl, sourceRange), MyBatisSourceMapKind.DECODED);
    }

    public void appendSynthetic(
            @NotNull String value,
            @NotNull String fileUrl,
            @NotNull MyBatisTextRange sourceRange) {
        append(value, new MyBatisSourceRange(fileUrl, sourceRange), MyBatisSourceMapKind.SYNTHETIC);
    }

    public @NotNull MyBatisMappedText build() {
        return new MyBatisMappedText(
                text.toString(),
                new MyBatisSourceMap(text.length(), segments));
    }

    private void append(
            @NotNull String value,
            @NotNull MyBatisSourceRange sourceRange,
            @NotNull MyBatisSourceMapKind kind) {
        if (value.isEmpty()) {
            return;
        }
        int virtualStart = text.length();
        text.append(value);
        MyBatisSourceMapSegment segment = new MyBatisSourceMapSegment(
                new MyBatisTextRange(virtualStart, text.length()),
                sourceRange,
                kind);
        if (!mergeExact(segment)) {
            segments.add(segment);
        }
    }

    private boolean mergeExact(@NotNull MyBatisSourceMapSegment incoming) {
        if (incoming.kind() != MyBatisSourceMapKind.EXACT || segments.isEmpty()) {
            return false;
        }
        MyBatisSourceMapSegment previous = segments.getLast();
        if (previous.kind() != MyBatisSourceMapKind.EXACT
                || !previous.sourceRange().fileUrl().equals(incoming.sourceRange().fileUrl())
                || previous.virtualRange().endOffset() != incoming.virtualRange().startOffset()
                || previous.sourceRange().range().endOffset()
                != incoming.sourceRange().range().startOffset()) {
            return false;
        }
        segments.set(segments.size() - 1, new MyBatisSourceMapSegment(
                new MyBatisTextRange(
                        previous.virtualRange().startOffset(),
                        incoming.virtualRange().endOffset()),
                new MyBatisSourceRange(
                        previous.sourceRange().fileUrl(),
                        new MyBatisTextRange(
                                previous.sourceRange().range().startOffset(),
                                incoming.sourceRange().range().endOffset())),
                MyBatisSourceMapKind.EXACT));
        return true;
    }
}
