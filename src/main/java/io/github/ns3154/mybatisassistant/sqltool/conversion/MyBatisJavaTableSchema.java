package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Java 类到 DDL 的不可变中间模型。
 */
public record MyBatisJavaTableSchema(
        @NotNull String tableName,
        @NotNull Optional<String> schema,
        @NotNull Optional<String> comment,
        @NotNull List<MyBatisJavaFieldSchema> fields,
        @NotNull List<MyBatisJavaIndexSchema> indexes) {
    public MyBatisJavaTableSchema {
        if (tableName.isBlank() || fields.isEmpty()) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.java.table.empty"));
        }
        schema = schema.filter(value -> !value.isBlank());
        comment = comment.filter(value -> !value.isBlank());
        fields = List.copyOf(fields);
        indexes = List.copyOf(indexes);
    }
}
