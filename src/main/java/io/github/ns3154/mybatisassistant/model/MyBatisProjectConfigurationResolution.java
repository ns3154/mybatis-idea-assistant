package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public sealed interface MyBatisProjectConfigurationResolution {
    record Found(@NotNull MyBatisProjectConfigurationModel model)
            implements MyBatisProjectConfigurationResolution {
        public Found {
            Objects.requireNonNull(model, "model");
        }
    }

    record IndexNotReady() implements MyBatisProjectConfigurationResolution {
    }

    record SourceInvalid() implements MyBatisProjectConfigurationResolution {
    }
}
