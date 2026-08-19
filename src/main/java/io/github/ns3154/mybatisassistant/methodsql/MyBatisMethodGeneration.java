package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 可供 IDE 预览和后续安全写入的完整生成结果。
 */
public record MyBatisMethodGeneration(
        @NotNull String returnType,
        @NotNull List<MyBatisMethodParameter> parameters,
        @NotNull String javaMethod,
        @NotNull String xmlStatement,
        @NotNull String sqlPreview,
        boolean dynamic) {
    public MyBatisMethodGeneration {
        parameters = List.copyOf(parameters);
    }
}
