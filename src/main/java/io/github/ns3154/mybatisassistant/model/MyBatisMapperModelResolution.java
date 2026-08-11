package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public sealed interface MyBatisMapperModelResolution {
    record Found(@NotNull MyBatisMapperModel model) implements MyBatisMapperModelResolution {
        public Found {
            Objects.requireNonNull(model, "model");
        }
    }

    record NotMapper() implements MyBatisMapperModelResolution {
    }

    record IndexNotReady() implements MyBatisMapperModelResolution {
    }

    record SourceInvalid() implements MyBatisMapperModelResolution {
    }

    record UnsupportedSource() implements MyBatisMapperModelResolution {
    }
}
