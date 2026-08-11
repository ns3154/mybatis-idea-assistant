package io.github.ns3154.mybatisassistant.sqltool.testgen;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 从已解析 PSI 提取的确定性测试骨架输入。
 */
public record MyBatisMapperTestRequest(
        @NotNull String packageName,
        @NotNull String mapperQualifiedName,
        @NotNull String mapperSimpleName,
        @NotNull String methodName,
        @NotNull String stableSignature,
        boolean returnsVoid,
        @NotNull List<MyBatisMapperTestParameter> parameters,
        @NotNull MyBatisJUnitPlatform platform) {
    public MyBatisMapperTestRequest {
        parameters = List.copyOf(parameters);
        if (mapperQualifiedName.isBlank()
                || mapperSimpleName.isBlank()
                || methodName.isBlank()
                || stableSignature.isBlank()) {
            throw new IllegalArgumentException("Mapper 与方法标识不能为空");
        }
    }
}
