package io.github.ns3154.mybatisassistant.generator;

import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseTable;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;
import org.jetbrains.annotations.NotNull;

/**
 * 一张已由用户明确选择的表及其生成配置。
 */
public record MyBatisGenerationRequest(
        @NotNull String dataSourceId,
        @NotNull MyBatisSqlDialect dialect,
        @NotNull MyBatisDatabaseTable table,
        @NotNull MyBatisGenerationConfiguration configuration) {
    public MyBatisGenerationRequest {
        if (dataSourceId.isBlank()) {
            throw new IllegalArgumentException("数据源标识不能为空");
        }
    }
}
