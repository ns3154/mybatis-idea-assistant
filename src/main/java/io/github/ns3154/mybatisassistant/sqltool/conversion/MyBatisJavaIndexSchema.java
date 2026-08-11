package io.github.ns3154.mybatisassistant.sqltool.conversion;

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
            throw new IllegalArgumentException("索引名称和列不能为空");
        }
        columns = List.copyOf(columns);
    }
}
