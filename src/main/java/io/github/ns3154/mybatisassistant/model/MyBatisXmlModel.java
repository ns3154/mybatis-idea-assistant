package io.github.ns3154.mybatisassistant.model;

import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class MyBatisXmlModel {
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    private MyBatisXmlModel() {
    }

    public static boolean isMapperRoot(@NotNull XmlTag tag) {
        return "mapper".equals(tag.getLocalName());
    }

    public static boolean isStatement(@NotNull XmlTag tag) {
        return STATEMENT_TAGS.contains(tag.getLocalName());
    }

    public static @Nullable String namespace(@NotNull XmlTag mapperTag) {
        return normalizedAttribute(mapperTag, "namespace");
    }

    public static @Nullable String statementId(@NotNull XmlTag statementTag) {
        return normalizedAttribute(statementTag, "id");
    }

    private static @Nullable String normalizedAttribute(@NotNull XmlTag tag, @NotNull String name) {
        String value = tag.getAttributeValue(name);
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
