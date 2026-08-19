package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MyBatisXmlSymbolKey {
    private static final char SEPARATOR = '\u0000';

    private MyBatisXmlSymbolKey() {
    }

    public static @NotNull String of(
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @Nullable String id) {
        String normalizedNamespace = normalizedSegment(namespace, "namespace");
        if (!kind.isNamed()) {
            if (id != null) {
                throw new IllegalArgumentException(MyBatisModelMessages.message(
                        "model.error.xml.namespace.id"));
            }
            return kind.name() + SEPARATOR + normalizedNamespace;
        }
        if (id == null) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.xml.named.id"));
        }
        return kind.name() + SEPARATOR + normalizedNamespace + SEPARATOR + normalizedSegment(id, "id");
    }

    private static @NotNull String normalizedSegment(@NotNull String value, @NotNull String name) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.xml.segment.empty", name));
        }
        if (normalized.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.xml.segment.separator", name));
        }
        return normalized;
    }
}
