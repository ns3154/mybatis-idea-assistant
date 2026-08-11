package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public enum MyBatisXmlSymbolKind {
    NAMESPACE(Set.of()),
    STATEMENT(Set.of("select", "insert", "update", "delete")),
    RESULT_MAP(Set.of("resultMap")),
    SQL_FRAGMENT(Set.of("sql"));

    private final Set<String> mapperChildTags;

    MyBatisXmlSymbolKind(@NotNull Set<String> mapperChildTags) {
        this.mapperChildTags = mapperChildTags;
    }

    public boolean isNamed() {
        return this != NAMESPACE;
    }

    public boolean matchesMapperChildTag(@NotNull String tagName) {
        return mapperChildTags.contains(tagName);
    }

    public static @Nullable MyBatisXmlSymbolKind fromMapperChildTag(@NotNull String tagName) {
        for (MyBatisXmlSymbolKind kind : values()) {
            if (kind.matchesMapperChildTag(tagName)) {
                return kind;
            }
        }
        return null;
    }
}
