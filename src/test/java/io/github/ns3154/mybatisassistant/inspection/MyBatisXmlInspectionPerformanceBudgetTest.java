package io.github.ns3154.mybatisassistant.inspection;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.testutil.MyBatisPerformanceProgressIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MyBatisXmlInspectionPerformanceBudgetTest extends BasePlatformTestCase {
    private static final int SQL_LINE_COUNT = 2_000;
    private static final int SAMPLE_COUNT = 20;

    public void testTwoThousandLineLocalInspectionP95RunsInBackgroundWithinBudget()
            throws Exception {
        List<XmlTag> statements = new ArrayList<>();
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            XmlFile file = (XmlFile) myFixture.addFileToProject(
                    "src/main/resources/mapper/Performance" + sample + ".xml",
                    largeUpdateXml("update" + sample));
            statements.add(statement(file));
        }

        Future<InspectionBenchmark> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    long[] durations = new long[statements.size()];
                    List<InspectionObservation> observations = new ArrayList<>();
                    for (int index = 0; index < statements.size(); index++) {
                        XmlTag statement = statements.get(index);
                        MyBatisPerformanceProgressIndicator indicator =
                                new MyBatisPerformanceProgressIndicator();
                        long started = System.nanoTime();
                        InspectionObservation observation = ProgressManager.getInstance()
                                .runProcess(
                                        () -> ReadAction.compute(() -> inspect(
                                                statement)),
                                        indicator);
                        durations[index] = System.nanoTime() - started;
                        observations.add(observation);
                    }
                    return new InspectionBenchmark(
                            durations,
                            observations);
                });

        InspectionBenchmark benchmark = future.get(60, TimeUnit.SECONDS);
        for (InspectionObservation observation : benchmark.observations()) {
            assertFalse("2000 行 XML 局部检查不得运行在 EDT", observation.dispatchThread());
            assertTrue("局部检查必须持有平台读锁", observation.readAccessAllowed());
            assertEquals("带 WHERE 的大型 update 不应误报", 0, observation.problemCount());
        }
        assertP95Below("2000 行 XML 局部检查", benchmark.durations(), 1_000.0);
    }

    public void testLocalInspectionPropagatesCancellationAtVisitorBoundary()
            throws Exception {
        XmlFile file = (XmlFile) myFixture.addFileToProject(
                "src/main/resources/mapper/CanceledPerformance.xml",
                largeUpdateXml("cancelUpdate"));
        XmlTag statement = statement(file);

        Future<CancellationObservation> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    MyBatisPerformanceProgressIndicator indicator =
                            new MyBatisPerformanceProgressIndicator();
                    boolean dispatchThread = ApplicationManager.getApplication()
                            .isDispatchThread();
                    try {
                        ProgressManager.getInstance().runProcess(
                                () -> ReadAction.compute(() -> inspect(
                                        statement,
                                        indicator::cancel)),
                                indicator);
                        return new CancellationObservation(
                                dispatchThread,
                                false,
                                indicator.canceledCheckCount());
                    } catch (ProcessCanceledException expected) {
                        return new CancellationObservation(
                                dispatchThread,
                                true,
                                indicator.canceledCheckCount());
                    }
                });

        CancellationObservation observation = future.get(30, TimeUnit.SECONDS);
        assertFalse("取消夹具必须运行在后台", observation.dispatchThread());
        assertTrue("Inspection visitor 入口必须传播 PCE", observation.canceled());
        assertTrue("PCE 必须由 visitor 的真实 checkCanceled 触发",
                observation.canceledCheckCount() > 0);
    }

    private InspectionObservation inspect(XmlTag statement) {
        return inspect(statement, () -> {
        });
    }

    private InspectionObservation inspect(XmlTag statement, Runnable beforeAccept) {
        boolean dispatchThread = ApplicationManager.getApplication().isDispatchThread();
        boolean readAccessAllowed = ApplicationManager.getApplication().isReadAccessAllowed();
        ProblemsHolder holder = new ProblemsHolder(
                InspectionManager.getInstance(getProject()),
                statement.getContainingFile(),
                true);
        PsiElementVisitor visitor = new MyBatisDangerousStatementInspection()
                .buildVisitor(holder, true);
        beforeAccept.run();
        statement.accept(visitor);
        return new InspectionObservation(
                dispatchThread,
                readAccessAllowed,
                holder.getResults().size());
    }

    private static XmlTag statement(XmlFile file) {
        XmlTag mapper = file.getRootTag();
        assertNotNull(mapper);
        XmlTag statement = mapper.findFirstSubTag("update");
        assertNotNull(statement);
        return statement;
    }

    private static String largeUpdateXml(String statementId) {
        StringBuilder source = new StringBuilder()
                .append("<mapper namespace=\"com.example.PerformanceMapper\">\n")
                .append("    <update id=\"")
                .append(statementId)
                .append("\">\n")
                .append("        UPDATE performance_budget SET\n");
        for (int line = 0; line < SQL_LINE_COUNT - 2; line++) {
            source.append("        <if test=\"flag")
                    .append(line)
                    .append(" != null\">column_")
                    .append(line)
                    .append(" = #{value")
                    .append(line)
                    .append("},</if>\n");
        }
        source.append("        id = #{id} WHERE id = #{id}\n")
                .append("    </update>\n")
                .append("</mapper>\n");
        return source.toString();
    }

    private void assertP95Below(String path, long[] durations, double thresholdMillis) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        int percentileIndex = (int) Math.ceil(sorted.length * 0.95) - 1;
        double p95Millis = sorted[percentileIndex] / 1_000_000.0;
        System.out.println(path + " P95=" + p95Millis + "ms，样本数=" + sorted.length);
        assertTrue(
                path + " P95 为 " + p95Millis + "ms，阈值为 " + thresholdMillis + "ms",
                p95Millis < thresholdMillis);
    }

    private record InspectionBenchmark(
            long[] durations,
            List<InspectionObservation> observations) {
    }

    private record InspectionObservation(
            boolean dispatchThread,
            boolean readAccessAllowed,
            int problemCount) {
    }

    private record CancellationObservation(
            boolean dispatchThread,
            boolean canceled,
            int canceledCheckCount) {
    }

}
