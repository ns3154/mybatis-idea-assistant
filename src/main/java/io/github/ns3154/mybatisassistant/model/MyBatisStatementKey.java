package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

public final class MyBatisStatementKey {
    private static final char SEPARATOR = '\u0000';

    private MyBatisStatementKey() {
    }

    public static @NotNull String of(@NotNull String namespace, @NotNull String statementId) {
        return namespace + SEPARATOR + statementId;
    }
}
