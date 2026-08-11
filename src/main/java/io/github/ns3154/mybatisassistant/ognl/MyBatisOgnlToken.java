package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

/**
 * 保留原始文本与精确范围的 OGNL token。
 */
public record MyBatisOgnlToken(
        @NotNull MyBatisOgnlTokenKind kind,
        @NotNull String text,
        @NotNull MyBatisOgnlRange range) {
}
