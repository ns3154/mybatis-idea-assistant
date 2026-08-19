package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Java 注解中可确定提取的索引。
 */
public record MyBatisJavaIndexSchema(
        @NotNull String name,
        @NotNull List<String> columns,
        boolean unique) {
    public MyBatisJavaIndexSchema {
        if (name.isBlank() || columns.isEmpty() || columns.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.java.index.empty"));
        }
        columns = List.copyOf(columns);
    }
}
