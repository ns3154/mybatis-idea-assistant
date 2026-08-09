package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

public final class MyBatisStatementKey {
    private static final char SEPARATOR = '\u0000';
    private static final String NAMESPACE_PREFIX = "namespace" + SEPARATOR;
    private static final String STATEMENT_PREFIX = "statement" + SEPARATOR;

    private MyBatisStatementKey() {
    }

    public static @NotNull String forNamespace(@NotNull String namespace) {
        return NAMESPACE_PREFIX + namespace;
    }

    public static @NotNull String forStatement(
            @NotNull String namespace,
            @NotNull String statementId) {
        return STATEMENT_PREFIX + namespace + SEPARATOR + statementId;
    }

    public static @NotNull String of(@NotNull String namespace, @NotNull String statementId) {
        return forStatement(namespace, statementId);
    }
}
