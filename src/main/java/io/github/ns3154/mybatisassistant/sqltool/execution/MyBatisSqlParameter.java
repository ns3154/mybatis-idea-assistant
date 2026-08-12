package io.github.ns3154.mybatisassistant.sqltool.execution;

import io.github.ns3154.mybatisassistant.MyBatisAssistantBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 已解析的 JDBC 参数；字符串化时始终脱敏。
 */
public record MyBatisSqlParameter(
        @NotNull MyBatisSqlParameterType type,
        @Nullable Object value,
        int jdbcType) {
    @Override
    public String toString() {
        return "MyBatisSqlParameter[type=" + type + ", value="
                + MyBatisAssistantBundle.message("common.redacted") + ", jdbcType="
                + jdbcType + "]";
    }
}
