package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntryKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class MyBatisBootConfigurationScanner {
    private static final Set<String> ROOT_KEYS = Set.of("mybatis", "mybatis-plus");

    private MyBatisBootConfigurationScanner() {
    }

    public static @NotNull Set<MyBatisBootConfigurationEntry> scan(
            @NotNull CharSequence content,
            @NotNull String extension) {
        ProgressManager.checkCanceled();
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "properties" -> scanProperties(content);
            case "yml", "yaml" -> scanYaml(content);
            default -> Set.of();
        };
    }

    private static @NotNull Set<MyBatisBootConfigurationEntry> scanProperties(
            @NotNull CharSequence content) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content.toString()));
        } catch (IOException | IllegalArgumentException ignored) {
            return Set.of();
        }
        Set<MyBatisBootConfigurationEntry> entries = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            ProgressManager.checkCanceled();
            addProperty(key, properties.getProperty(key), entries);
        }
        return Set.copyOf(entries);
    }

    private static @NotNull Set<MyBatisBootConfigurationEntry> scanYaml(
            @NotNull CharSequence content) {
        Set<MyBatisBootConfigurationEntry> entries = new LinkedHashSet<>();
        Deque<YamlPathPart> path = new ArrayDeque<>();
        for (String rawLine : content.toString().split("\\R", -1)) {
            ProgressManager.checkCanceled();
            if (rawLine.indexOf('\t') >= 0) {
                continue;
            }
            String withoutComment = stripYamlComment(rawLine);
            String trimmed = withoutComment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("---".equals(trimmed) || "...".equals(trimmed)) {
                path.clear();
                continue;
            }
            int indent = leadingSpaces(withoutComment);
            while (!path.isEmpty() && path.peekLast().indent() >= indent) {
                path.removeLast();
            }
            if (trimmed.startsWith("- ")) {
                addYamlValue(joinPath(path), trimmed.substring(2), entries);
                continue;
            }
            int separator = yamlKeySeparator(trimmed);
            if (separator <= 0) {
                continue;
            }
            String key = unquote(trimmed.substring(0, separator).trim());
            String value = trimmed.substring(separator + 1).trim();
            String fullPath = joinPath(path, key);
            if (value.isEmpty()) {
                path.addLast(new YamlPathPart(indent, key));
            } else {
                addYamlValue(fullPath, value, entries);
            }
        }
        return Set.copyOf(entries);
    }

    private static void addProperty(
            @NotNull String key,
            @Nullable String rawValue,
            @NotNull Set<MyBatisBootConfigurationEntry> entries) {
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        int rootSeparator = normalizedKey.indexOf('.');
        if (rootSeparator <= 0 || !ROOT_KEYS.contains(normalizedKey.substring(0, rootSeparator))) {
            return;
        }
        addValues(normalizedKey.substring(rootSeparator + 1), rawValue, entries);
    }

    private static void addYamlValue(
            @NotNull String path,
            @NotNull String rawValue,
            @NotNull Set<MyBatisBootConfigurationEntry> entries) {
        int rootSeparator = path.indexOf('.');
        if (rootSeparator <= 0 || !ROOT_KEYS.contains(path.substring(0, rootSeparator))) {
            return;
        }
        String value = rawValue.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        addValues(path.substring(rootSeparator + 1), value, entries);
    }

    private static void addValues(
            @NotNull String propertyName,
            @Nullable String rawValue,
            @NotNull Set<MyBatisBootConfigurationEntry> entries) {
        MyBatisBootConfigurationEntryKind kind = kind(propertyName);
        if (kind == null || rawValue == null) {
            return;
        }
        String[] values = kind == MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE
                || kind == MyBatisBootConfigurationEntryKind.TYPE_HANDLERS_PACKAGE
                ? rawValue.split("[,;\\s]+")
                : rawValue.split(",");
        for (String rawItem : values) {
            ProgressManager.checkCanceled();
            String item = unquote(rawItem.trim());
            if (!item.isEmpty() && !item.contains("${") && !item.contains("#{")) {
                entries.add(new MyBatisBootConfigurationEntry(kind, item));
            }
        }
    }

    private static @Nullable MyBatisBootConfigurationEntryKind kind(@NotNull String propertyName) {
        String relaxedName = propertyName.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        return switch (relaxedName) {
            case "configlocation" -> MyBatisBootConfigurationEntryKind.CONFIG_LOCATION;
            case "mapperlocations" -> MyBatisBootConfigurationEntryKind.MAPPER_LOCATION;
            case "typealiasespackage" -> MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE;
            case "typehandlerspackage" -> MyBatisBootConfigurationEntryKind.TYPE_HANDLERS_PACKAGE;
            default -> null;
        };
    }

    private static @NotNull String joinPath(@NotNull Deque<YamlPathPart> path) {
        List<String> parts = new ArrayList<>();
        for (YamlPathPart part : path) {
            parts.add(part.key());
        }
        return String.join(".", parts).toLowerCase(Locale.ROOT);
    }

    private static @NotNull String joinPath(
            @NotNull Deque<YamlPathPart> path,
            @NotNull String key) {
        String prefix = joinPath(path);
        return (prefix.isEmpty() ? key : prefix + '.' + key).toLowerCase(Locale.ROOT);
    }

    private static int leadingSpaces(@NotNull String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static int yamlKeySeparator(@NotNull String value) {
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current == '\'' || current == '"') && (quote == 0 || quote == current)) {
                quote = quote == 0 ? current : 0;
            } else if (current == ':' && quote == 0) {
                return index;
            }
        }
        return -1;
    }

    private static @NotNull String stripYamlComment(@NotNull String value) {
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current == '\'' || current == '"') && (quote == 0 || quote == current)) {
                quote = quote == 0 ? current : 0;
            } else if (current == '#' && quote == 0) {
                return value.substring(0, index);
            }
        }
        return value;
    }

    private static @NotNull String unquote(@NotNull String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == last && (first == '\'' || first == '"')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private record YamlPathPart(int indent, @NotNull String key) {
    }
}
