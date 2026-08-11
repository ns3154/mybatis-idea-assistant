package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 可在 IDE 中完整预览的确定性 Join SQL。
 */
public record MyBatisJoinGeneration(
        @NotNull String sql,
        @NotNull List<String> outputLabels) {
    public MyBatisJoinGeneration {
        outputLabels = List.copyOf(outputLabels);
    }
}
