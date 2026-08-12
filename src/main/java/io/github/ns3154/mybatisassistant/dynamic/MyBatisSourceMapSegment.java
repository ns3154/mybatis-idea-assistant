package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 一段压缩后的虚拟 SQL 到 XML 映射。
 */
public record MyBatisSourceMapSegment(
        @NotNull MyBatisTextRange virtualRange,
        @NotNull MyBatisSourceRange sourceRange,
        @NotNull MyBatisSourceMapKind kind) {
    public MyBatisSourceMapSegment {
        if (virtualRange.length() == 0 || sourceRange.range().length() == 0) {
            throw new IllegalArgumentException(MyBatisDynamicMessages.message(
                    "dynamic.error.source.map.segment.empty"));
        }
        if (kind == MyBatisSourceMapKind.EXACT
                && virtualRange.length() != sourceRange.range().length()) {
            throw new IllegalArgumentException(MyBatisDynamicMessages.message(
                    "dynamic.error.source.map.segment.exact.length"));
        }
    }
}
