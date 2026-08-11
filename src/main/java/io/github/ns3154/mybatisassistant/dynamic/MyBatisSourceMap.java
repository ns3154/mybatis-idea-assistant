package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖整个虚拟 SQL 的不可变双向 source map。
 */
public final class MyBatisSourceMap {
    private final int virtualLength;
    private final List<MyBatisSourceMapSegment> segments;

    public MyBatisSourceMap(
            int virtualLength,
            @NotNull List<MyBatisSourceMapSegment> segments) {
        if (virtualLength < 0) {
            throw new IllegalArgumentException("虚拟 SQL 长度不能为负数");
        }
        this.virtualLength = virtualLength;
        this.segments = List.copyOf(segments);
        validateCoverage();
    }

    public int virtualLength() {
        return virtualLength;
    }

    public @NotNull List<MyBatisSourceMapSegment> segments() {
        return segments;
    }

    public @NotNull List<MyBatisSourceMapping> sourceMappings(
            @NotNull MyBatisTextRange virtualRange) {
        requireVirtualRange(virtualRange);
        if (virtualRange.length() == 0) {
            return List.of();
        }
        List<MyBatisSourceMapping> result = new ArrayList<>();
        for (MyBatisSourceMapSegment segment : segments) {
            segment.virtualRange().intersection(virtualRange).ifPresent(intersection ->
                    result.add(toSourceMapping(segment, intersection)));
        }
        return List.copyOf(result);
    }

    public @NotNull List<MyBatisSourceMapping> virtualMappings(
            @NotNull MyBatisSourceRange sourceRange) {
        if (sourceRange.range().length() == 0) {
            return List.of();
        }
        List<MyBatisSourceMapping> result = new ArrayList<>();
        for (MyBatisSourceMapSegment segment : segments) {
            if (!segment.sourceRange().fileUrl().equals(sourceRange.fileUrl())) {
                continue;
            }
            segment.sourceRange().range().intersection(sourceRange.range()).ifPresent(intersection ->
                    result.add(toVirtualMapping(segment, intersection)));
        }
        return List.copyOf(result);
    }

    private void validateCoverage() {
        int expectedStart = 0;
        for (MyBatisSourceMapSegment segment : segments) {
            if (segment.virtualRange().startOffset() != expectedStart) {
                throw new IllegalArgumentException("source map 必须连续覆盖整个虚拟 SQL");
            }
            expectedStart = segment.virtualRange().endOffset();
            if (expectedStart > virtualLength) {
                throw new IllegalArgumentException("source map 超出虚拟 SQL 长度");
            }
        }
        if (expectedStart != virtualLength) {
            throw new IllegalArgumentException("source map 未覆盖整个虚拟 SQL");
        }
    }

    private void requireVirtualRange(@NotNull MyBatisTextRange range) {
        if (range.endOffset() > virtualLength) {
            throw new IllegalArgumentException("查询范围超出虚拟 SQL 长度");
        }
    }

    private static @NotNull MyBatisSourceMapping toSourceMapping(
            @NotNull MyBatisSourceMapSegment segment,
            @NotNull MyBatisTextRange virtualIntersection) {
        MyBatisTextRange source = segment.sourceRange().range();
        if (segment.kind() == MyBatisSourceMapKind.EXACT) {
            int deltaStart = virtualIntersection.startOffset()
                    - segment.virtualRange().startOffset();
            int deltaEnd = virtualIntersection.endOffset()
                    - segment.virtualRange().startOffset();
            source = new MyBatisTextRange(
                    source.startOffset() + deltaStart,
                    source.startOffset() + deltaEnd);
        }
        return new MyBatisSourceMapping(
                virtualIntersection,
                new MyBatisSourceRange(segment.sourceRange().fileUrl(), source),
                segment.kind());
    }

    private static @NotNull MyBatisSourceMapping toVirtualMapping(
            @NotNull MyBatisSourceMapSegment segment,
            @NotNull MyBatisTextRange sourceIntersection) {
        MyBatisTextRange virtual = segment.virtualRange();
        MyBatisTextRange source = segment.sourceRange().range();
        if (segment.kind() == MyBatisSourceMapKind.EXACT) {
            int deltaStart = sourceIntersection.startOffset() - source.startOffset();
            int deltaEnd = sourceIntersection.endOffset() - source.startOffset();
            virtual = new MyBatisTextRange(
                    virtual.startOffset() + deltaStart,
                    virtual.startOffset() + deltaEnd);
            source = sourceIntersection;
        }
        return new MyBatisSourceMapping(
                virtual,
                new MyBatisSourceRange(segment.sourceRange().fileUrl(), source),
                segment.kind());
    }
}
