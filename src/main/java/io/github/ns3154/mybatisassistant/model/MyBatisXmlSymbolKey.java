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
                throw new IllegalArgumentException("namespace 符号不能包含 id");
            }
            return kind.name() + SEPARATOR + normalizedNamespace;
        }
        if (id == null) {
            throw new IllegalArgumentException("具名 MyBatis XML 符号必须包含 id");
        }
        return kind.name() + SEPARATOR + normalizedNamespace + SEPARATOR + normalizedSegment(id, "id");
    }

    private static @NotNull String normalizedSegment(@NotNull String value, @NotNull String name) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("MyBatis XML " + name + " 不能为空");
        }
        if (normalized.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("MyBatis XML " + name + " 不能包含索引分隔符");
        }
        return normalized;
    }
}
