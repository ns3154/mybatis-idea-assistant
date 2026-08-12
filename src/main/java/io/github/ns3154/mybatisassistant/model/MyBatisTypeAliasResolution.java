package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public sealed interface MyBatisTypeAliasResolution {
    record Unique(@NotNull String canonicalType) implements MyBatisTypeAliasResolution {
        public Unique {
            Objects.requireNonNull(canonicalType, "canonicalType");
            if (canonicalType.isBlank()) {
                throw new IllegalArgumentException(MyBatisModelMessages.message(
                        "model.error.type.alias.target.empty"));
            }
        }
    }

    record Multiple(@NotNull List<String> canonicalTypes) implements MyBatisTypeAliasResolution {
        public Multiple {
            canonicalTypes = List.copyOf(canonicalTypes);
            if (canonicalTypes.size() < 2) {
                throw new IllegalArgumentException(MyBatisModelMessages.message(
                        "model.error.type.alias.multiple"));
            }
        }
    }

    record Unresolved() implements MyBatisTypeAliasResolution {
    }

    record IndexNotReady() implements MyBatisTypeAliasResolution {
    }

    record SourceInvalid() implements MyBatisTypeAliasResolution {
    }
}
