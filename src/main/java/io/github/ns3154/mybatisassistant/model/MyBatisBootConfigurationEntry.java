package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record MyBatisBootConfigurationEntry(
        @NotNull MyBatisBootConfigurationEntryKind kind,
        @NotNull String value) {
    public MyBatisBootConfigurationEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("MyBatis Boot 配置值不能为空");
        }
    }

    public @NotNull String indexKey() {
        return MyBatisBootConfigurationKey.of(kind, value);
    }
}
