package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationColumnOverride;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationConfiguration;
import io.github.ns3154.mybatisassistant.generator.MyBatisGenerationNames;
import io.github.ns3154.mybatisassistant.generator.MyBatisJavaTypeMapping;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 方法名解析只消费调用方显式提供的表与字段词典。
 */
public record MyBatisMethodSchema(
        @NotNull String tableName,
        @NotNull List<MyBatisMethodField> fields) {
    public MyBatisMethodSchema {
        if (tableName.isBlank()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.schema.table.empty"));
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.schema.fields.empty"));
        }
        Set<String> properties = new HashSet<>();
        Set<String> tokens = new HashSet<>();
        for (MyBatisMethodField field : fields) {
            if (!properties.add(field.propertyName())) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.error.schema.property.duplicate", field.propertyName()));
            }
            if (!tokens.add(field.methodToken())) {
                throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                        "methodsql.error.schema.token.duplicate", field.methodToken()));
            }
        }
    }

    public static @NotNull MyBatisMethodSchema from(
            @NotNull MyBatisDatabaseTable table,
            @NotNull MyBatisGenerationConfiguration configuration) {
        List<MyBatisMethodField> fields = new ArrayList<>();
        Map<String, MyBatisGenerationColumnOverride> overrides = configuration.columnOverrides();
        for (MyBatisDatabaseColumn column : table.columns()) {
            String normalized = column.name().toLowerCase(Locale.ROOT);
            if (configuration.excludedColumns().contains(normalized)) {
                continue;
            }
            MyBatisGenerationColumnOverride override = overrides.getOrDefault(
                    normalized, MyBatisGenerationColumnOverride.empty());
            String property = override.propertyName().orElseGet(() ->
                    MyBatisGenerationNames.lowerCamel(column.name()));
            MyBatisJavaTypeMapping type = MyBatisJavaTypeMapping.resolve(column, override);
            fields.add(new MyBatisMethodField(
                    property,
                    upperFirst(property),
                    column.name(),
                    type.canonicalType(),
                    override.typeHandler(),
                    column.jdbcType(),
                    column.nullable(),
                    column.primaryKey(),
                    column.foreignKey()));
        }
        return new MyBatisMethodSchema(table.name(), fields);
    }

    private static @NotNull String upperFirst(@NotNull String value) {
        int first = value.codePointAt(0);
        return new StringBuilder()
                .appendCodePoint(Character.toUpperCase(first))
                .append(value.substring(Character.charCount(first)))
                .toString();
    }
}
