package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public record MyBatisMapperMethodModel(
        @NotNull String name,
        @NotNull String declaringType,
        @NotNull String returnType,
        @NotNull MyBatisEntityModel returnEntity,
        @NotNull List<MyBatisParameterModel> parameters,
        @NotNull MyBatisStatementSourceKind statementSource,
        boolean inherited) {
    public MyBatisMapperMethodModel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(returnEntity, "returnEntity");
        Objects.requireNonNull(statementSource, "statementSource");
        parameters = List.copyOf(parameters);
        if (name.isBlank() || declaringType.isBlank() || returnType.isBlank()) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.mapper.method.identity.empty"));
        }
    }

    public @NotNull String stableSignature() {
        StringBuilder signature = new StringBuilder(name).append('(');
        for (int index = 0; index < parameters.size(); index++) {
            if (index > 0) {
                signature.append(',');
            }
            signature.append(parameters.get(index).canonicalType());
        }
        return signature.append(')').toString();
    }
}
