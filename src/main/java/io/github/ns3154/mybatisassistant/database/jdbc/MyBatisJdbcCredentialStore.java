package io.github.ns3154.mybatisassistant.database.jdbc;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 只通过 IntelliJ PasswordSafe 读写 Community JDBC 密码。
 */
public final class MyBatisJdbcCredentialStore {
    private static final String SUBSYSTEM = "MyBatis Assistant JDBC";
    private static final ConcurrentMap<CredentialKey, PendingPassword> PENDING =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<CredentialKey, CompletableFuture<Void>> WRITES =
            new ConcurrentHashMap<>();

    private MyBatisJdbcCredentialStore() {
    }

    public static @NotNull Optional<String> password(
            @NotNull Project project,
            @NotNull String dataSourceId,
            @NotNull String username) {
        CredentialKey key = key(project, dataSourceId, username);
        PendingPassword pending = PENDING.get(key);
        if (pending != null) {
            return pending.removal
                    ? Optional.empty()
                    : Optional.of(new String(pending.value));
        }
        Credentials credentials = PasswordSafe.getInstance().get(attributes(key));
        if (credentials == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(credentials.getPasswordAsString());
    }

    public static @NotNull CompletableFuture<Void> storePassword(
            @NotNull Project project,
            @NotNull String dataSourceId,
            @NotNull String username,
            @Nullable char[] password) {
        CredentialKey key = key(project, dataSourceId, username);
        PendingPassword pending = new PendingPassword(
                password == null ? new char[0] : password.clone(),
                password == null);
        PENDING.put(key, pending);
        CompletableFuture<Void> write;
        synchronized (WRITES) {
            CompletableFuture<Void> previous = WRITES.getOrDefault(
                    key, CompletableFuture.completedFuture(null));
            write = previous.handle((ignored, failure) -> null).thenRunAsync(() -> {
                Credentials credentials = pending.removal
                        ? null
                        : new Credentials(username, pending.value);
                PasswordSafe.getInstance().set(attributes(key), credentials);
            }, ApplicationManager.getApplication()::executeOnPooledThread);
            WRITES.put(key, write);
        }
        return write.whenComplete((ignored, failure) -> {
            PENDING.remove(key, pending);
            pending.clear();
            synchronized (WRITES) {
                if (WRITES.get(key) == write) {
                    WRITES.remove(key);
                }
            }
        });
    }

    static @NotNull CredentialAttributes attributes(
            @NotNull Project project,
            @NotNull String dataSourceId,
            @NotNull String username) {
        return attributes(key(project, dataSourceId, username));
    }

    private static @NotNull CredentialAttributes attributes(@NotNull CredentialKey key) {
        String serviceName = CredentialAttributesKt.generateServiceName(
                SUBSYSTEM, key.projectLocationHash + ':' + key.dataSourceId);
        return new CredentialAttributes(serviceName, key.username);
    }

    private static @NotNull CredentialKey key(
            @NotNull Project project,
            @NotNull String dataSourceId,
            @NotNull String username) {
        return new CredentialKey(project.getLocationHash(), dataSourceId, username);
    }

    private record CredentialKey(
            @NotNull String projectLocationHash,
            @NotNull String dataSourceId,
            @NotNull String username) {
    }

    private static final class PendingPassword {
        private final char[] value;
        private final boolean removal;

        private PendingPassword(char[] value, boolean removal) {
            this.value = value;
            this.removal = removal;
        }

        private void clear() {
            java.util.Arrays.fill(value, '\0');
        }
    }
}
