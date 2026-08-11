package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

/**
 * 安全合并结果不使用异常表示业务冲突。
 */
public sealed interface MyBatisSafeMergeResult {
    record Ready(@NotNull String text, boolean changed) implements MyBatisSafeMergeResult {
    }

    record Conflict(
            @NotNull MyBatisSafeMergeConflictCode code,
            @NotNull String message) implements MyBatisSafeMergeResult {
    }
}
