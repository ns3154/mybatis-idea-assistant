package io.github.ns3154.mybatisassistant.database.jdbc;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MyBatisJdbcCredentialStoreTest extends BasePlatformTestCase {
    public void testStoresReadsAndRemovesPasswordThroughPasswordSafe() throws Exception {
        String id = "test-" + UUID.randomUUID();
        String username = "tester";
        char[] source = "secret-value".toCharArray();
        try {
            MyBatisJdbcCredentialStore.storePassword(getProject(), id, username, source)
                    .get(5, TimeUnit.SECONDS);
            Optional<String> stored = com.intellij.util.concurrency.AppExecutorUtil
                    .getAppExecutorService()
                    .submit(() -> MyBatisJdbcCredentialStore.password(
                            getProject(), id, username))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(Optional.of("secret-value"), stored);
            assertEquals("secret-value", new String(source));
            assertTrue(MyBatisJdbcCredentialStore.attributes(getProject(), id, username)
                    .getServiceName().contains("MyBatis Assistant JDBC"));
        } finally {
            MyBatisJdbcCredentialStore.storePassword(getProject(), id, username, null)
                    .get(5, TimeUnit.SECONDS);
        }

        Optional<String> removed = com.intellij.util.concurrency.AppExecutorUtil
                .getAppExecutorService()
                .submit(() -> MyBatisJdbcCredentialStore.password(
                        getProject(), id, username))
                .get(5, TimeUnit.SECONDS);
        assertTrue(removed.isEmpty());
    }

    public void testSerializesRapidUpdatesAndKeepsLastPassword() throws Exception {
        String id = "test-" + UUID.randomUUID();
        String username = "tester";
        try {
            var first = MyBatisJdbcCredentialStore.storePassword(
                    getProject(), id, username, "first".toCharArray());
            var second = MyBatisJdbcCredentialStore.storePassword(
                    getProject(), id, username, "second".toCharArray());

            assertEquals(Optional.of("second"), MyBatisJdbcCredentialStore.password(
                    getProject(), id, username));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("second"), MyBatisJdbcCredentialStore.password(
                    getProject(), id, username));
        } finally {
            MyBatisJdbcCredentialStore.storePassword(getProject(), id, username, null)
                    .get(5, TimeUnit.SECONDS);
        }
    }
}
