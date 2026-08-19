package io.github.ns3154.mybatisassistant.sqltool.conversion;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
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
            throw new IllegalArgumentException(MyBatisAssistantBundle.message(
                    "sqltool.conversion.error.ddl.empty"));
        }
        warnings = List.copyOf(warnings);
    }
}
