package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 不依赖本机语言环境的命名与路径规则。
 */
public final class MyBatisGenerationNames {
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for", "goto",
            "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "record", "sealed", "permits", "var", "yield");

    private MyBatisGenerationNames() {
    }

    public static @NotNull String upperCamel(@NotNull String databaseName) {
        List<String> words = words(databaseName);
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        String candidate = result.isEmpty() ? "Generated" : result.toString();
        if (!Character.isJavaIdentifierStart(candidate.charAt(0))) {
            candidate = "Generated" + candidate;
        }
        return JAVA_KEYWORDS.contains(candidate.toLowerCase(Locale.ROOT))
                ? candidate + "Entity"
                : candidate;
    }

    public static @NotNull String lowerCamel(@NotNull String databaseName) {
        String upper = upperCamel(databaseName);
        String candidate = Character.toLowerCase(upper.charAt(0)) + upper.substring(1);
        return JAVA_KEYWORDS.contains(candidate) ? candidate + "_" : candidate;
    }

    public static @NotNull String requireRelativePath(@NotNull String path) {
        String normalized = path.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.root.relative"));
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.error.root.traversal"));
            }
        }
        return normalized;
    }

    static void requirePackageName(@NotNull String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.base.package.empty"));
        }
        for (String segment : value.split("\\.")) {
            requireJavaIdentifier(segment);
        }
    }

    static void requireJavaIdentifier(@NotNull String value) {
        if (value.isBlank() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.java.identifier.invalid", value));
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.error.java.identifier.invalid", value));
            }
        }
        if (JAVA_KEYWORDS.contains(value)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.java.identifier.keyword", value));
        }
    }

    static void requireJavaType(@NotNull String value) {
        String type = value.endsWith("[]") ? value.substring(0, value.length() - 2) : value;
        for (String segment : type.split("\\.")) {
            requireJavaIdentifier(segment);
        }
    }

    private static @NotNull List<String> words(@NotNull String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (!current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            offset += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
