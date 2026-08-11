package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S9 方法区使用独立标识，避免改变 S8 schema 生成区集合。
 */
final class MyBatisMethodRegionMerger {
    private MyBatisMethodRegionMerger() {
    }

    static boolean hasMarker(@NotNull String text, @NotNull String methodName) {
        return normalize(text).contains("<mybatis-assistant-method id=\"" + methodName + "\"");
    }

    static @NotNull MyBatisSafeMergeResult merge(
            @NotNull String existingText,
            @NotNull MyBatisGeneratedRegion.Style style,
            @NotNull String methodName,
            @NotNull String body,
            boolean declarationAlreadyExists) {
        String separator = existingText.contains("\r\n") ? "\r\n" : "\n";
        String existing = normalize(existingText);
        Pattern pattern = blockPattern(style, methodName);
        Matcher matcher = pattern.matcher(existing);
        List<Block> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(new Block(
                    matcher.start(), matcher.end(), matcher.group(2), matcher.group(3)));
        }
        int markerOccurrences = occurrences(
                existing, "<mybatis-assistant-method id=\"" + methodName + "\"");
        if (markerOccurrences != blocks.size() || blocks.size() > 1) {
            return conflict(MyBatisSafeMergeConflictCode.MALFORMED_MARKERS,
                    "方法生成区标识缺失、损坏或重复：" + methodName);
        }
        String rendered = render(style, methodName, body);
        String merged;
        if (blocks.size() == 1) {
            Block old = blocks.get(0);
            if (!old.hash().equals(MyBatisGeneratedRegion.sha256(old.body()))) {
                return conflict(MyBatisSafeMergeConflictCode.METHOD_REGION_MODIFIED,
                        "方法生成区已被手工修改：" + methodName);
            }
            merged = existing.substring(0, old.start()) + rendered
                    + existing.substring(old.end());
        } else {
            if (declarationAlreadyExists) {
                return conflict(MyBatisSafeMergeConflictCode.METHOD_DECLARATION_EXISTS,
                        "目标文件已存在同名手写声明：" + methodName);
            }
            int insertion = insertionOffset(existing, style);
            if (insertion < 0) {
                return conflict(MyBatisSafeMergeConflictCode.METHOD_TARGET_MISSING,
                        style == MyBatisGeneratedRegion.Style.JAVA
                                ? "Java 目标缺少类型结束边界"
                                : "XML 目标缺少 mapper 结束标签");
            }
            String prefix = existing.substring(0, insertion);
            merged = prefix + (prefix.endsWith("\n") ? "" : "\n")
                    + rendered + existing.substring(insertion);
        }
        if ("\r\n".equals(separator)) {
            merged = merged.replace("\n", "\r\n");
        }
        return new MyBatisSafeMergeResult.Ready(merged, !merged.equals(existingText));
    }

    private static @NotNull Pattern blockPattern(
            @NotNull MyBatisGeneratedRegion.Style style,
            @NotNull String methodName) {
        String id = Pattern.quote(methodName);
        String start;
        String end;
        if (style == MyBatisGeneratedRegion.Style.JAVA) {
            start = "^([ \\t]*)// <mybatis-assistant-method id=\\\"" + id
                    + "\\\" sha256=\\\"([0-9a-f]{64})\\\">\\n";
            end = "^\\1// </mybatis-assistant-method id=\\\"" + id + "\\\">\\n?";
        } else {
            start = "^([ \\t]*)<!-- <mybatis-assistant-method id=\\\"" + id
                    + "\\\" sha256=\\\"([0-9a-f]{64})\\\"> -->\\n";
            end = "^\\1<!-- </mybatis-assistant-method id=\\\"" + id + "\\\"> -->\\n?";
        }
        return Pattern.compile(start + "(.*?)" + end, Pattern.MULTILINE | Pattern.DOTALL);
    }

    private static @NotNull String render(
            @NotNull MyBatisGeneratedRegion.Style style,
            @NotNull String methodName,
            @NotNull String body) {
        if (!methodName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("方法生成区标识不是 Java 方法名：" + methodName);
        }
        String normalizedBody = MyBatisGeneratedRegion.normalize(body);
        String indent = "    ";
        String start = switch (style) {
            case JAVA -> "// <mybatis-assistant-method id=\"" + methodName
                    + "\" sha256=\"" + MyBatisGeneratedRegion.sha256(normalizedBody) + "\">";
            case XML -> "<!-- <mybatis-assistant-method id=\"" + methodName
                    + "\" sha256=\"" + MyBatisGeneratedRegion.sha256(normalizedBody) + "\"> -->";
        };
        String end = switch (style) {
            case JAVA -> "// </mybatis-assistant-method id=\"" + methodName + "\">";
            case XML -> "<!-- </mybatis-assistant-method id=\"" + methodName + "\"> -->";
        };
        return indent + start + "\n" + normalizedBody + indent + end + "\n";
    }

    private static int insertionOffset(
            @NotNull String text,
            @NotNull MyBatisGeneratedRegion.Style style) {
        return style == MyBatisGeneratedRegion.Style.JAVA
                ? text.lastIndexOf('}')
                : text.lastIndexOf("</mapper>");
    }

    private static int occurrences(@NotNull String text, @NotNull String token) {
        int count = 0;
        int cursor = text.indexOf(token);
        while (cursor >= 0) {
            count++;
            cursor = text.indexOf(token, cursor + token.length());
        }
        return count;
    }

    private static @NotNull String normalize(@NotNull String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static @NotNull MyBatisSafeMergeResult.Conflict conflict(
            @NotNull MyBatisSafeMergeConflictCode code,
            @NotNull String message) {
        return new MyBatisSafeMergeResult.Conflict(code, message);
    }

    private record Block(int start, int end, @NotNull String hash, @NotNull String body) {
    }
}
