package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 使用稳定文件 URL 表示的原始 XML 范围，不持有 PSI。
 */
public record MyBatisSourceRange(
        @NotNull String fileUrl,
        @NotNull MyBatisTextRange range) {
    public MyBatisSourceRange {
        if (fileUrl.isBlank()) {
            throw new IllegalArgumentException("源文件 URL 不能为空");
        }
    }
}
