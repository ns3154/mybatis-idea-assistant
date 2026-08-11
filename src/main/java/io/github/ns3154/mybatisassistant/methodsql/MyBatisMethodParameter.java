package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 可直接生成 @Param 的方法参数。
 */
public record MyBatisMethodParameter(
        @NotNull String name,
        @NotNull String javaType,
        @NotNull MyBatisMethodParameterRole role,
        @NotNull Optional<MyBatisMethodField> field) {
    public MyBatisMethodParameter {
        if (name.isBlank() || javaType.isBlank()) {
            throw new IllegalArgumentException("方法参数名称与类型不能为空");
        }
    }

    public @NotNull String declaration() {
        return "@org.apache.ibatis.annotations.Param(\"" + name + "\") "
                + javaType + " " + name;
    }
}
