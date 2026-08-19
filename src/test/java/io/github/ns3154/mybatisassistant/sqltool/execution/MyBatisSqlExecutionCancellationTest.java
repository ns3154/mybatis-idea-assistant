package io.github.ns3154.mybatisassistant.sqltool.execution;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisSqlExecutionCancellationTest extends BasePlatformTestCase {
    public void testParameterPanelPropagatesCancellation() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        assertThrows(ProcessCanceledException.class, () -> ProgressManager.getInstance()
                .runProcess(() -> {
                    indicator.cancel();
                    return MyBatisSqlExecutionPolicy.prepare(
                            "SELECT ?", "STRING:value");
                }, indicator));
    }
}
