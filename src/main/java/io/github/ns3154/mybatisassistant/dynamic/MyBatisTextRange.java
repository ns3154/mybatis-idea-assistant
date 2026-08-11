package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 与平台 PSI 无关的半开文本范围。
 */
public record MyBatisTextRange(int startOffset, int endOffset) {
    public MyBatisTextRange {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("文本范围必须满足 0 <= start <= end");
        }
    }

    public int length() {
        return endOffset - startOffset;
    }

    public boolean contains(int offset) {
        return offset >= startOffset && offset < endOffset;
    }

    public @NotNull Optional<MyBatisTextRange> intersection(@NotNull MyBatisTextRange other) {
        int start = Math.max(startOffset, other.startOffset);
        int end = Math.min(endOffset, other.endOffset);
        return start < end
                ? Optional.of(new MyBatisTextRange(start, end))
                : Optional.empty();
    }
}
