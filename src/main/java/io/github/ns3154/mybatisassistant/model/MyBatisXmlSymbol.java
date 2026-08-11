package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record MyBatisXmlSymbol(
        @NotNull MyBatisXmlSymbolKind kind,
        @NotNull String namespace,
        @Nullable String id) {

    public MyBatisXmlSymbol {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(namespace, "namespace");
        if ((kind.isNamed() && (id == null || id.isBlank()))
                || (!kind.isNamed() && id != null)) {
            throw new IllegalArgumentException("MyBatis XML 符号类型与 id 不匹配");
        }
        namespace = namespace.trim();
        id = id == null ? null : id.trim();
        MyBatisXmlSymbolKey.of(kind, namespace, id);
    }

    public static @NotNull MyBatisXmlSymbol namespace(@NotNull String namespace) {
        return new MyBatisXmlSymbol(MyBatisXmlSymbolKind.NAMESPACE, namespace, null);
    }

    public static @NotNull MyBatisXmlSymbol named(
            @NotNull MyBatisXmlSymbolKind kind,
            @NotNull String namespace,
            @NotNull String id) {
        return new MyBatisXmlSymbol(kind, namespace, id);
    }

    public @NotNull String indexKey() {
        return MyBatisXmlSymbolKey.of(kind, namespace, id);
    }
}
