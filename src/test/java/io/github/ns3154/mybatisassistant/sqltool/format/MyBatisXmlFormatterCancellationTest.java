package io.github.ns3154.mybatisassistant.sqltool.format;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class MyBatisXmlFormatterCancellationTest extends BasePlatformTestCase {
    public void testPropagatesCancellation() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                return MyBatisXmlFormatter.format("<mapper/>\n", 2);
            }, indicator);
            fail("取消后的 XML 格式化必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消属于平台控制流，格式化器不得转换成普通失败。
        }
    }
}
