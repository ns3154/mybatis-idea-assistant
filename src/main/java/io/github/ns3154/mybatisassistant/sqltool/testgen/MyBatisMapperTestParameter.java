package io.github.ns3154.mybatisassistant.sqltool.testgen;

import org.jetbrains.annotations.NotNull;

/**
 * Mapper 测试骨架所需的最小参数模型。
 */
public record MyBatisMapperTestParameter(
        @NotNull String name,
        @NotNull String canonicalType) {
    public MyBatisMapperTestParameter {
        if (name.isBlank() || canonicalType.isBlank()) {
            throw new IllegalArgumentException("参数名称和类型不能为空");
        }
    }
}
