package io.github.ns3154.mybatisassistant.sqltool.conversion;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Java 类生成 DDL 的候选文本与保守告警。
 */
public record MyBatisJavaDdlGeneration(
        @NotNull String ddl,
        @NotNull List<String> warnings,
        boolean confirmationRequired) {
    public MyBatisJavaDdlGeneration {
        if (ddl.isBlank()) {
            throw new IllegalArgumentException("DDL 不能为空");
        }
        warnings = List.copyOf(warnings);
    }
}
