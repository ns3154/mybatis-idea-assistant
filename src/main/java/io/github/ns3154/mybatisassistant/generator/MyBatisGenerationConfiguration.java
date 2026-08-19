package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 可导入导出的确定性生成配置。
 */
public record MyBatisGenerationConfiguration(
        @NotNull String basePackage,
        @NotNull String javaSourceRoot,
        @NotNull String resourceRoot,
        @NotNull Set<MyBatisGenerationArtifactKind> artifacts,
        @NotNull MyBatisGenerationTemplateGroup templateGroup,
        @NotNull String tablePrefix,
        @NotNull String entitySuffix,
        boolean generateComments,
        boolean escapeSqlKeywords,
        @NotNull Set<String> excludedColumns,
        @NotNull Map<String, MyBatisGenerationColumnOverride> columnOverrides) {
    public MyBatisGenerationConfiguration {
        basePackage = basePackage.trim();
        javaSourceRoot = MyBatisGenerationNames.requireRelativePath(javaSourceRoot);
        resourceRoot = MyBatisGenerationNames.requireRelativePath(resourceRoot);
        tablePrefix = tablePrefix.trim();
        entitySuffix = entitySuffix.trim();
        MyBatisGenerationNames.requirePackageName(basePackage);
        if (!entitySuffix.isEmpty()) {
            MyBatisGenerationNames.requireJavaIdentifier("Entity" + entitySuffix);
        }
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "generator.error.artifacts.empty"));
        }
        artifacts = Set.copyOf(EnumSet.copyOf(artifacts));
        excludedColumns = excludedColumns.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, MyBatisGenerationColumnOverride> normalized = new TreeMap<>();
        columnOverrides.forEach((column, override) -> {
            if (column == null || column.isBlank() || override == null) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "generator.error.column.override.empty"));
            }
            normalized.put(column.toLowerCase(Locale.ROOT), override);
        });
        columnOverrides = Map.copyOf(normalized);
    }

    public static @NotNull MyBatisGenerationConfiguration standard(
            @NotNull String basePackage) {
        return new MyBatisGenerationConfiguration(
                basePackage,
                "src/main/java",
                "src/main/resources",
                EnumSet.allOf(MyBatisGenerationArtifactKind.class),
                MyBatisGenerationTemplateGroup.STANDARD,
                "",
                "",
                true,
                true,
                Set.of(),
                Map.of());
    }
}
