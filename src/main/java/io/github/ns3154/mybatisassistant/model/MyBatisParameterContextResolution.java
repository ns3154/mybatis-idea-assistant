package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

public sealed interface MyBatisParameterContextResolution {
    record Found(@NotNull MyBatisParameterContext context)
            implements MyBatisParameterContextResolution {
    }

    record UnsupportedSource() implements MyBatisParameterContextResolution {
    }

    record IndexNotReady() implements MyBatisParameterContextResolution {
    }

    record SourceInvalid() implements MyBatisParameterContextResolution {
    }
}
