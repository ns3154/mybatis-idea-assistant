package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 无时间戳、无默认值漂移的 S8 生成配置导入导出格式。
 */
public final class MyBatisGenerationConfigurationCodec {
    private static final String FORMAT = "1";

    private MyBatisGenerationConfigurationCodec() {
    }

    public static @NotNull String encode(
            @NotNull MyBatisGenerationConfiguration configuration) {
        Map<String, String> values = new TreeMap<>();
        values.put("format", FORMAT);
        values.put("basePackage", configuration.basePackage());
        values.put("javaSourceRoot", configuration.javaSourceRoot());
        values.put("resourceRoot", configuration.resourceRoot());
        values.put("artifacts", configuration.artifacts().stream()
                .sorted()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(",")));
        values.put("templateGroup", configuration.templateGroup().name());
        values.put("tablePrefix", configuration.tablePrefix());
        values.put("entitySuffix", configuration.entitySuffix());
        values.put("generateComments", Boolean.toString(configuration.generateComments()));
        values.put("escapeSqlKeywords", Boolean.toString(configuration.escapeSqlKeywords()));
        values.put("excludedColumns", configuration.excludedColumns().stream()
                .sorted()
                .map(MyBatisGenerationConfigurationCodec::escape)
                .collect(java.util.stream.Collectors.joining(",")));
        configuration.columnOverrides().forEach((column, override) -> {
            String key = "override." + escape(column) + ".";
            override.propertyName().ifPresent(value -> values.put(key + "property", value));
            override.javaType().ifPresent(value -> values.put(key + "javaType", value));
            override.typeHandler().ifPresent(value -> values.put(key + "typeHandler", value));
        });
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + escape(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    public static @NotNull MyBatisGenerationConfiguration decode(@NotNull String text) {
        Map<String, String> values = parse(text);
        if (!FORMAT.equals(required(values, "format"))) {
            throw new IllegalArgumentException("不支持的生成配置版本");
        }
        rejectUnknownKeys(values.keySet());
        EnumSet<MyBatisGenerationArtifactKind> artifacts = EnumSet.noneOf(
                MyBatisGenerationArtifactKind.class);
        for (String value : required(values, "artifacts").split(",")) {
            if (!value.isBlank()) {
                artifacts.add(parseEnum(MyBatisGenerationArtifactKind.class, value));
            }
        }
        Set<String> excluded = new LinkedHashSet<>();
        String excludedValue = required(values, "excludedColumns");
        if (!excludedValue.isBlank()) {
            Arrays.stream(excludedValue.split(",", -1))
                    .map(MyBatisGenerationConfigurationCodec::unescape)
                    .forEach(excluded::add);
        }
        Map<String, MutableOverride> mutableOverrides = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!key.startsWith("override.")) {
                return;
            }
            int fieldSeparator = key.lastIndexOf('.');
            String column = unescape(key.substring("override.".length(), fieldSeparator));
            String field = key.substring(fieldSeparator + 1);
            MutableOverride override = mutableOverrides.computeIfAbsent(
                    column,
                    ignored -> new MutableOverride());
            switch (field) {
                case "property" -> override.property = Optional.of(value);
                case "javaType" -> override.javaType = Optional.of(value);
                case "typeHandler" -> override.typeHandler = Optional.of(value);
                default -> throw new IllegalArgumentException("未知列覆盖字段：" + field);
            }
        });
        Map<String, MyBatisGenerationColumnOverride> overrides = new LinkedHashMap<>();
        mutableOverrides.forEach((column, value) -> overrides.put(
                column,
                new MyBatisGenerationColumnOverride(
                        value.property,
                        value.javaType,
                        value.typeHandler)));
        return new MyBatisGenerationConfiguration(
                required(values, "basePackage"),
                required(values, "javaSourceRoot"),
                required(values, "resourceRoot"),
                artifacts,
                parseEnum(MyBatisGenerationTemplateGroup.class,
                        required(values, "templateGroup")),
                required(values, "tablePrefix"),
                required(values, "entitySuffix"),
                parseBoolean(values, "generateComments"),
                parseBoolean(values, "escapeSqlKeywords"),
                excluded,
                overrides);
    }

    private static @NotNull Map<String, String> parse(@NotNull String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int lineNumber = 0;
        for (String line : normalized.split("\n", -1)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("生成配置第 " + lineNumber + " 行缺少等号");
            }
            String key = line.substring(0, separator);
            String value = unescape(line.substring(separator + 1));
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("生成配置键重复：" + key);
            }
        }
        return values;
    }

    private static void rejectUnknownKeys(@NotNull Set<String> keys) {
        Set<String> fixed = Set.of(
                "format", "basePackage", "javaSourceRoot", "resourceRoot", "artifacts",
                "templateGroup", "tablePrefix", "entitySuffix", "generateComments",
                "escapeSqlKeywords", "excludedColumns");
        for (String key : keys) {
            if (!fixed.contains(key) && !key.matches(
                    "override\\..+\\.(property|javaType|typeHandler)")) {
                throw new IllegalArgumentException("未知生成配置键：" + key);
            }
        }
    }

    private static @NotNull String required(
            @NotNull Map<String, String> values,
            @NotNull String key) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("生成配置缺少必填键：" + key);
        }
        return values.get(key);
    }

    private static boolean parseBoolean(
            @NotNull Map<String, String> values,
            @NotNull String key) {
        String value = required(values, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("生成配置布尔值不合法：" + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static <E extends Enum<E>> @NotNull E parseEnum(
            @NotNull Class<E> type,
            @NotNull String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("生成配置枚举值不合法：" + value, invalid);
        }
    }

    private static @NotNull String escape(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static @NotNull String unescape(@NotNull String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("生成配置包含非法转义：" + value, invalid);
        }
    }

    private static final class MutableOverride {
        private Optional<String> property = Optional.empty();
        private Optional<String> javaType = Optional.empty();
        private Optional<String> typeHandler = Optional.empty();
    }
}
