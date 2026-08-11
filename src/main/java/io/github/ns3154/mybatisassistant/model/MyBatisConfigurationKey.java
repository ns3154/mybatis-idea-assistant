package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class MyBatisConfigurationKey {
    private static final char SEPARATOR = '\u0000';
    private static final String ALL = "*";

    private MyBatisConfigurationKey() {
    }

    public static @NotNull String of(
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull String name) {
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("MyBatis 配置索引键无效");
        }
        if (kind == MyBatisConfigurationEntryKind.TYPE_ALIAS) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return kind.name() + SEPARATOR + normalized;
    }

    public static @NotNull String all(@NotNull MyBatisConfigurationEntryKind kind) {
        return kind.name() + SEPARATOR + ALL;
    }
}
