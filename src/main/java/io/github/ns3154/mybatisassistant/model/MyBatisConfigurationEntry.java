package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

public record MyBatisConfigurationEntry(
        @NotNull MyBatisConfigurationEntryKind kind,
        @NotNull String name,
        @Nullable String value) {
    public MyBatisConfigurationEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        name = normalizeName(kind, name);
        value = value == null ? null : normalized(value);
        if (kind == MyBatisConfigurationEntryKind.TYPE_ALIAS && value == null) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.type.alias.target.missing"));
        }
        if (kind != MyBatisConfigurationEntryKind.TYPE_ALIAS && value != null) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.configuration.extra.value"));
        }
    }

    public @NotNull String indexKey() {
        return MyBatisConfigurationKey.of(kind, name);
    }

    private static @NotNull String normalizeName(
            @NotNull MyBatisConfigurationEntryKind kind,
            @NotNull String name) {
        String normalized = normalized(name);
        return kind == MyBatisConfigurationEntryKind.TYPE_ALIAS
                ? normalized.toLowerCase(Locale.ROOT)
                : normalized;
    }

    private static @NotNull String normalized(@NotNull String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.configuration.entry.empty"));
        }
        return normalized;
    }
}
