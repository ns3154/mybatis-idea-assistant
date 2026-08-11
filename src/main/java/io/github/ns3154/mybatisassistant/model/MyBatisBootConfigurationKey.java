package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

public final class MyBatisBootConfigurationKey {
    private static final char SEPARATOR = '\u0000';
    private static final String ALL = "*";

    private MyBatisBootConfigurationKey() {
    }

    public static @NotNull String of(
            @NotNull MyBatisBootConfigurationEntryKind kind,
            @NotNull String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("MyBatis Boot 配置索引键无效");
        }
        return kind.name() + SEPARATOR + normalized;
    }

    public static @NotNull String all(@NotNull MyBatisBootConfigurationEntryKind kind) {
        return kind.name() + SEPARATOR + ALL;
    }
}
