package io.github.ns3154.mybatisassistant.sqltool.conversion;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisConversionCancellationTest extends BasePlatformTestCase {
    public void testCreateTableParserPropagatesCancellation() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisCreateTableParser.parse(
                            "CREATE TABLE sample (id BIGINT, name VARCHAR(255))");
                }, indicator));
    }

    public void testSelectProjectionParserPropagatesCancellation() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisSelectProjectionParser.parse(
                            "SELECT id, name FROM users");
                }, indicator));
    }
}
