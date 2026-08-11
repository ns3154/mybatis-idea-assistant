package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class MyBatisConfigurationEntries {
    private MyBatisConfigurationEntries() {
    }

    public static @NotNull List<MyBatisConfigurationEntry> fromTag(
            @NotNull String sectionName,
            @NotNull String tagName,
            @NotNull Function<String, String> attributeValue) {
        List<MyBatisConfigurationEntry> result = new ArrayList<>();
        if ("typeAliases".equals(sectionName)) {
            collectTypeAlias(tagName, attributeValue, result);
        } else if ("mappers".equals(sectionName)) {
            collectMapper(tagName, attributeValue, result);
        }
        return List.copyOf(result);
    }

    private static void collectTypeAlias(
            @NotNull String tagName,
            @NotNull Function<String, String> attributeValue,
            @NotNull List<MyBatisConfigurationEntry> target) {
        if ("typeAlias".equals(tagName)) {
            String type = normalized(attributeValue.apply("type"));
            if (type == null) {
                return;
            }
            String alias = normalized(attributeValue.apply("alias"));
            target.add(new MyBatisConfigurationEntry(
                    MyBatisConfigurationEntryKind.TYPE_ALIAS,
                    alias == null ? simpleName(type) : alias,
                    type));
        } else if ("package".equals(tagName)) {
            addNamed(MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE, attributeValue.apply("name"), target);
        }
    }

    private static void collectMapper(
            @NotNull String tagName,
            @NotNull Function<String, String> attributeValue,
            @NotNull List<MyBatisConfigurationEntry> target) {
        if ("mapper".equals(tagName)) {
            addNamed(MyBatisConfigurationEntryKind.MAPPER_RESOURCE, attributeValue.apply("resource"), target);
            addNamed(MyBatisConfigurationEntryKind.MAPPER_URL, attributeValue.apply("url"), target);
            addNamed(MyBatisConfigurationEntryKind.MAPPER_CLASS, attributeValue.apply("class"), target);
        } else if ("package".equals(tagName)) {
            addNamed(MyBatisConfigurationEntryKind.MAPPER_PACKAGE, attributeValue.apply("name"), target);
        }
    }

    private static void addNamed(
            @NotNull MyBatisConfigurationEntryKind kind,
            @Nullable String name,
            @NotNull List<MyBatisConfigurationEntry> target) {
        String normalized = normalized(name);
        if (normalized != null) {
            target.add(new MyBatisConfigurationEntry(kind, normalized, null));
        }
    }

    private static @NotNull String simpleName(@NotNull String qualifiedName) {
        int separator = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$'));
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private static @Nullable String normalized(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
