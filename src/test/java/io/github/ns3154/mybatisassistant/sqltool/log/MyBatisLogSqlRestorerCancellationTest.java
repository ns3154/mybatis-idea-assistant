package io.github.ns3154.mybatisassistant.sqltool.log;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisLogSqlRestorerCancellationTest extends BasePlatformTestCase {
    public void testPropagatesCancellationWithoutProducingPartialOutput() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                return MyBatisLogSqlRestorer.restore("""
                        ==> Preparing: SELECT * FROM users WHERE id = ?
                        ==> Parameters: 1(Long)
                        """);
            }, indicator);
            fail("取消后的日志还原必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台控制流，不得转换成普通诊断或部分 SQL。
        }
    }
}
