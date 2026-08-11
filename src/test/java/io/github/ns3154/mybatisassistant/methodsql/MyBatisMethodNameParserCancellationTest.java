package io.github.ns3154.mybatisassistant.methodsql;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

public final class MyBatisMethodNameParserCancellationTest extends BasePlatformTestCase {
    public void testPropagatesCancellationFromFieldMatching() {
        MyBatisMethodSchema schema = new MyBatisMethodSchema(
                "user_account",
                List.of(new MyBatisMethodField(
                        "status",
                        "Status",
                        "status",
                        "java.lang.String",
                        Optional.empty(),
                        Types.VARCHAR,
                        false,
                        false,
                        false)));
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                return MyBatisMethodNameParser.parse("findByStatus", schema);
            }, indicator);
            fail("取消后的方法名字段匹配必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台控制流，解析器不得将其转换成普通诊断。
        }
    }
}
