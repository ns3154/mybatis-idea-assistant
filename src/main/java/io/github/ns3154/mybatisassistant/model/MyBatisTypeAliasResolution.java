package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public sealed interface MyBatisTypeAliasResolution {
    record Unique(@NotNull String canonicalType) implements MyBatisTypeAliasResolution {
        public Unique {
            Objects.requireNonNull(canonicalType, "canonicalType");
            if (canonicalType.isBlank()) {
                throw new IllegalArgumentException("TypeAlias 目标类型不能为空");
            }
        }
    }

    record Multiple(@NotNull List<String> canonicalTypes) implements MyBatisTypeAliasResolution {
        public Multiple {
            canonicalTypes = List.copyOf(canonicalTypes);
            if (canonicalTypes.size() < 2) {
                throw new IllegalArgumentException("多 TypeAlias 结果至少需要两个目标类型");
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
