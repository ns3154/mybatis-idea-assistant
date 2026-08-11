package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class MyBatisXmlModel {
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private MyBatisXmlModel() {
    }

    public static boolean isMapperRoot(@NotNull XmlTag tag) {
        return "mapper".equals(tag.getName());
    }

    public static boolean isStatement(@NotNull XmlTag tag) {
        return STATEMENT_TAGS.contains(tag.getName());
    }

    public static boolean isSymbolTag(
            @NotNull XmlTag tag,
            @NotNull MyBatisXmlSymbolKind kind) {
        return kind.matchesMapperChildTag(tag.getName());
    }

    public static @Nullable String namespace(@NotNull XmlTag mapperTag) {
        return normalizedAttribute(mapperTag, "namespace");
    }

    public static @Nullable String statementId(@NotNull XmlTag statementTag) {
        return symbolId(statementTag);
    }

    public static @Nullable String symbolId(@NotNull XmlTag symbolTag) {
        return normalizedAttribute(symbolTag, "id");
    }

    private static @Nullable String normalizedAttribute(@NotNull XmlTag tag, @NotNull String name) {
        XmlAttribute attribute = tag.getAttribute(name);
        String value = attribute == null || !name.equals(attribute.getName())
                ? null
                : attribute.getValue();
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
