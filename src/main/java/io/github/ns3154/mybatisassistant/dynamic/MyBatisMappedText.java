package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 带完整 source map 的虚拟 SQL 文本。
 */
public record MyBatisMappedText(
        @NotNull String text,
        @NotNull MyBatisSourceMap sourceMap) {
    public MyBatisMappedText {
        if (text.length() != sourceMap.virtualLength()) {
            throw new IllegalArgumentException("虚拟文本长度必须与 source map 一致");
        }
    }
}
