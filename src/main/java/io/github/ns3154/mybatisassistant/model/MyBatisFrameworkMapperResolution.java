package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

/**
 * 框架 Mapper 解析结果；不确定状态不能降级成普通 Mapper 猜测。
 */
public sealed interface MyBatisFrameworkMapperResolution {
    record Found(@NotNull MyBatisFrameworkMapperBinding binding)
            implements MyBatisFrameworkMapperResolution {
    }

    record NotFrameworkMapper() implements MyBatisFrameworkMapperResolution {
    }

    record Unsupported(@NotNull String reason) implements MyBatisFrameworkMapperResolution {
    }

    record IndexNotReady() implements MyBatisFrameworkMapperResolution {
    }

    record SourceInvalid() implements MyBatisFrameworkMapperResolution {
    }
}
