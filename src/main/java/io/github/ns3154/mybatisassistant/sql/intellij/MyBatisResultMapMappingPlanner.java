package io.github.ns3154.mybatisassistant.sql.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationNames;
import io.github.ns3154.mybatisassistant.reference.MyBatisResultPropertySupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 为简单 ResultMap 计算不会覆盖既有映射的缺失字段补齐计划。
 */
public final class MyBatisResultMapMappingPlanner {
    private static final Set<String> DIRECT_COLUMN_TAGS = Set.of(
            "id", "result", "association", "collection", "discriminator");
    private static final Set<String> SIMPLE_MAPPING_TAGS = Set.of("id", "result");

    private MyBatisResultMapMappingPlanner() {
    }

    public static boolean isDirectColumnMapping(@NotNull XmlTag tag) {
        XmlTag parent = tag.getParentTag();
        return parent != null
                && "resultMap".equals(parent.getName())
                && DIRECT_COLUMN_TAGS.contains(tag.getName());
    }

    public static @NotNull Optional<Plan> plan(
            @NotNull XmlTag resultMap,
            @NotNull MyBatisResultMapSchemaResolver.ResolvedTable resolvedTable) {
        ProgressManager.checkCanceled();
        String extendsValue = resultMap.getAttributeValue("extends");
        if (!resultMap.isValid()
                || !"resultMap".equals(resultMap.getName())
                || (extendsValue != null && !extendsValue.isBlank())
                || !PsiTreeUtil.findChildrenOfType(
                        resultMap,
                        PsiErrorElement.class).isEmpty()) {
            return Optional.empty();
        }
        for (XmlText text : PsiTreeUtil.getChildrenOfTypeAsList(resultMap, XmlText.class)) {
            if (!text.getValue().isBlank()) {
                return Optional.empty();
            }
        }
        Optional<List<String>> writable =
                MyBatisResultPropertySupport.rootWritableProperties(resultMap);
        if (writable.isEmpty()) {
            return Optional.empty();
        }
        Set<String> writableProperties = Set.copyOf(writable.orElseThrow());
        Map<String, String> mappedColumns = new LinkedHashMap<>();
        Set<String> mappedProperties = new HashSet<>();
        for (XmlTag child : resultMap.getSubTags()) {
            ProgressManager.checkCanceled();
            if (!SIMPLE_MAPPING_TAGS.contains(child.getName())
                    || child.getSubTags().length != 0
                    || !child.getValue().getText().isBlank()) {
                return Optional.empty();
            }
            String column = staticValue(child.getAttributeValue("column"));
            String property = staticValue(child.getAttributeValue("property"));
            if (!isSimpleColumnName(column) || property == null
                    || !writableProperties.contains(property)
                    || mappedColumns.putIfAbsent(normalize(column), property) != null
                    || !mappedProperties.add(property)) {
                return Optional.empty();
            }
        }

        List<MyBatisDatabaseColumn> columns = resolvedTable.table().columns().stream()
                .sorted(Comparator.comparing(MyBatisDatabaseColumn::primaryKey).reversed()
                        .thenComparingInt(MyBatisDatabaseColumn::position)
                        .thenComparing(MyBatisDatabaseColumn::name))
                .toList();
        Map<String, MyBatisDatabaseColumn> databaseColumns = new LinkedHashMap<>();
        for (MyBatisDatabaseColumn column : columns) {
            ProgressManager.checkCanceled();
            if (!isSimpleColumnName(column.name())
                    || databaseColumns.putIfAbsent(normalize(column.name()), column) != null) {
                return Optional.empty();
            }
        }
        if (!databaseColumns.keySet().containsAll(mappedColumns.keySet())) {
            return Optional.empty();
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> generatedProperties = new HashSet<>();
        for (MyBatisDatabaseColumn column : columns) {
            ProgressManager.checkCanceled();
            if (mappedColumns.containsKey(normalize(column.name()))) {
                continue;
            }
            String property = propertyName(column.name(), writableProperties);
            if (property == null
                    || mappedProperties.contains(property)
                    || !generatedProperties.add(property)) {
                return Optional.empty();
            }
            entries.add(new Entry(column.name(), property, column.primaryKey()));
        }
        return Optional.of(new Plan(resolvedTable.identity(), entries));
    }

    private static @Nullable String propertyName(
            @NotNull String column,
            @NotNull Set<String> writableProperties) {
        if (writableProperties.contains(column)) {
            return column;
        }
        String generated = MyBatisGenerationNames.lowerCamel(column);
        return writableProperties.contains(generated) ? generated : null;
    }

    private static boolean isSimpleColumnName(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int first = value.codePointAt(0);
        if (!Character.isUnicodeIdentifierStart(first) && first != '_') {
            return false;
        }
        for (int offset = Character.charCount(first); offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isUnicodeIdentifierPart(codePoint) && codePoint != '$') {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static @Nullable String staticValue(@Nullable String value) {
        if (value == null || value.isBlank()
                || !value.equals(value.trim())
                || value.contains("${") || value.contains("#{")) {
            return null;
        }
        return value;
    }

    private static @NotNull String normalize(@NotNull String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record Plan(
            @NotNull MyBatisResultMapSchemaResolver.TableIdentity tableIdentity,
            @NotNull List<Entry> entries) {
        public Plan {
            entries = List.copyOf(entries);
        }
    }

    public record Entry(
            @NotNull String column,
            @NotNull String property,
            boolean primaryKey) {
        public Entry {
            if (column.isBlank() || property.isBlank()) {
                throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                        "resultmap.mapping.entry.empty"));
            }
        }
    }
}
