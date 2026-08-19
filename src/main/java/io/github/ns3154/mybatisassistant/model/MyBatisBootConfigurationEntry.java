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
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.boot.configuration.value.empty"));
        }
    }

    public @NotNull String indexKey() {
        return MyBatisBootConfigurationKey.of(kind, value);
    }
}
