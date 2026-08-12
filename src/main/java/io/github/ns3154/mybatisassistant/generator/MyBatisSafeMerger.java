package io.github.ns3154.mybatisassistant.generator;

import com.intellij.openapi.progress.ProgressManager;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只替换指纹未被用户修改的稳定生成区。
 */
public final class MyBatisSafeMerger {
    private static final Pattern START = Pattern.compile(
            "^(?:\\s*//|\\s*<!--) <mybatis-assistant-generated id=\\\"([^\\\"]+)\\\""
                    + " sha256=\\\"([0-9a-f]{64})\\\">(?: -->)?\\s*$");
    private static final Pattern END = Pattern.compile(
            "^(?:\\s*//|\\s*<!--) </mybatis-assistant-generated id=\\\"([^\\\"]+)\\\">"
                    + "(?: -->)?\\s*$");

    private MyBatisSafeMerger() {
    }

    public static @NotNull MyBatisSafeMergeResult merge(
            @NotNull String existingText,
            @NotNull String desiredText) {
        String separator = existingText.contains("\r\n") ? "\r\n" : "\n";
        String existing = normalize(existingText);
        String desired = normalize(desiredText);
        ParseResult existingRegions = parse(existing);
        ParseResult desiredRegions = parse(desired);
        if (existingRegions.error != null || desiredRegions.error != null) {
            return new MyBatisSafeMergeResult.Conflict(
                    MyBatisSafeMergeConflictCode.MALFORMED_MARKERS,
                    existingRegions.error != null ? existingRegions.error : desiredRegions.error);
        }
        if (existingRegions.regions.isEmpty()) {
            return new MyBatisSafeMergeResult.Conflict(
                    MyBatisSafeMergeConflictCode.FILE_WITHOUT_MARKERS,
                    MyBatisAssistantBundle.message(
                            "generator.error.marker.missing"));
        }
        if (!existingRegions.regions.keySet().equals(desiredRegions.regions.keySet())) {
            return new MyBatisSafeMergeResult.Conflict(
                    MyBatisSafeMergeConflictCode.MARKER_SET_CHANGED,
                    MyBatisAssistantBundle.message(
                            "generator.error.marker.set.changed"));
        }
        for (Region region : existingRegions.regions.values()) {
            ProgressManager.checkCanceled();
            if (!region.declaredHash.equals(MyBatisGeneratedRegion.sha256(region.body))) {
                return new MyBatisSafeMergeResult.Conflict(
                        MyBatisSafeMergeConflictCode.GENERATED_REGION_MODIFIED,
                        MyBatisAssistantBundle.message(
                                "generator.error.region.modified", region.id));
            }
        }
        for (Region region : desiredRegions.regions.values()) {
            if (!region.declaredHash.equals(MyBatisGeneratedRegion.sha256(region.body))) {
                return new MyBatisSafeMergeResult.Conflict(
                        MyBatisSafeMergeConflictCode.MALFORMED_MARKERS,
                        MyBatisAssistantBundle.message(
                                "generator.error.region.fingerprint", region.id));
            }
        }
        StringBuilder merged = new StringBuilder(existing);
        List<Region> descending = new ArrayList<>(existingRegions.regions.values());
        descending.sort((first, second) -> Integer.compare(second.blockStart, first.blockStart));
        for (Region oldRegion : descending) {
            ProgressManager.checkCanceled();
            Region replacement = desiredRegions.regions.get(oldRegion.id);
            merged.replace(oldRegion.blockStart, oldRegion.blockEnd, replacement.block);
        }
        String result = merged.toString();
        if ("\r\n".equals(separator)) {
            result = result.replace("\n", "\r\n");
        }
        return new MyBatisSafeMergeResult.Ready(result, !result.equals(existingText));
    }

    private static @NotNull ParseResult parse(@NotNull String text) {
        Map<String, Region> regions = new LinkedHashMap<>();
        Map<String, OpenRegion> open = new HashMap<>();
        int cursor = 0;
        while (cursor < text.length()) {
            ProgressManager.checkCanceled();
            int lineEnd = text.indexOf('\n', cursor);
            int next = lineEnd < 0 ? text.length() : lineEnd + 1;
            String line = text.substring(cursor, lineEnd < 0 ? text.length() : lineEnd);
            Matcher start = START.matcher(line);
            Matcher end = END.matcher(line);
            if (start.matches()) {
                String id = start.group(1);
                if (open.putIfAbsent(id, new OpenRegion(
                        id, start.group(2), cursor, next)) != null || regions.containsKey(id)) {
                    return ParseResult.error(MyBatisAssistantBundle.message(
                            "generator.error.marker.duplicate", id));
                }
            } else if (end.matches()) {
                String id = end.group(1);
                OpenRegion started = open.remove(id);
                if (started == null || !open.isEmpty()) {
                    return ParseResult.error(MyBatisAssistantBundle.message(
                            "generator.error.marker.boundary", id));
                }
                String body = text.substring(started.bodyStart, cursor);
                regions.put(id, new Region(
                        id,
                        started.declaredHash,
                        body,
                        started.blockStart,
                        next,
                        text.substring(started.blockStart, next)));
            }
            cursor = next;
        }
        if (!open.isEmpty()) {
            return ParseResult.error(MyBatisAssistantBundle.message(
                    "generator.error.marker.end.missing", open.keySet().iterator().next()));
        }
        return new ParseResult(regions, null);
    }

    private static @NotNull String normalize(@NotNull String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record OpenRegion(
            String id,
            String declaredHash,
            int blockStart,
            int bodyStart) {
    }

    private record Region(
            String id,
            String declaredHash,
            String body,
            int blockStart,
            int blockEnd,
            String block) {
    }

    private record ParseResult(Map<String, Region> regions, String error) {
        private static @NotNull ParseResult error(@NotNull String message) {
            return new ParseResult(Map.of(), message);
        }
    }
}
