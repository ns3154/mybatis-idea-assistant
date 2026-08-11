package io.github.ns3154.mybatisassistant.dynamic;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public final class MyBatisDynamicSqlCompilerPerformanceTest extends BasePlatformTestCase {
    public void testSamePsiAndDependencyGenerationReuseCachedResult() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select * from users where id = #{id}</select>
                </mapper>
                """);
        XmlTag statement = statement(file);

        MyBatisDynamicSqlCompileResult first = MyBatisDynamicSqlCompiler.compile(statement);
        MyBatisDynamicSqlCompileResult second = MyBatisDynamicSqlCompiler.compile(statement);

        assertSame(first, second);
    }

    public void testUnsavedStatementEditInvalidatesCachedProgram() {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select id from users</select>
                </mapper>
                """);
        XmlTag statement = statement(file);
        MyBatisDynamicSqlCompileResult first = MyBatisDynamicSqlCompiler.compile(statement);

        replace(file, "select id from users", "select id, name from users");
        MyBatisDynamicSqlCompileResult second = MyBatisDynamicSqlCompiler.compile(statement);

        assertNotSame(first, second);
        assertTrue(staticSql(second).contains("select id, name from users"));
    }

    public void testUnsavedIncludedFragmentEditInvalidatesCachedProgram() {
        PsiFile shared = myFixture.addFileToProject("shared/SharedMapper.xml", """
                <mapper namespace="com.example.SharedMapper">
                    <sql id="Columns">id</sql>
                </mapper>
                """);
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select <include refid="com.example.SharedMapper.Columns"/> from users
                    </select>
                </mapper>
                """);
        XmlTag statement = statement(file);
        MyBatisDynamicSqlCompileResult first = MyBatisDynamicSqlCompiler.compile(statement);
        assertTrue(staticSql(first).contains("select id from users"));

        replace(shared, ">id<", ">id, name<");
        MyBatisDynamicSqlCompileResult second = MyBatisDynamicSqlCompiler.compile(statement);

        assertNotSame(first, second);
        assertTrue(staticSql(second).contains("select id, name from users"));
    }

    public void testNewDuplicateFragmentInvalidatesIndexedIncludeResult() {
        myFixture.addFileToProject("one/SharedMapper.xml", """
                <mapper namespace="com.example.SharedMapper"><sql id="Columns">id</sql></mapper>
                """);
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">
                        select <include refid="com.example.SharedMapper.Columns"/> from users
                    </select>
                </mapper>
                """);
        XmlTag statement = statement(file);
        MyBatisDynamicSqlCompileResult first = MyBatisDynamicSqlCompiler.compile(statement);
        assertEmpty(program(first).diagnostics());

        myFixture.addFileToProject("two/SharedMapper.xml", """
                <mapper namespace="com.example.SharedMapper"><sql id="Columns">name</sql></mapper>
                """);
        MyBatisDynamicSqlCompileResult second = MyBatisDynamicSqlCompiler.compile(statement);

        assertNotSame(first, second);
        assertTrue(program(second).diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisDynamicSqlDiagnosticCode.INCLUDE_TARGET_NOT_UNIQUE));
    }

    public void testDumbModeResultIsTransientAndSmartCacheRecovers() throws Exception {
        XmlFile file = configure("""
                <mapper namespace="com.example.UserMapper">
                    <select id="find">select 1</select>
                </mapper>
                """);
        XmlTag statement = statement(file);
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            MyBatisDynamicSqlCompileResult first = MyBatisDynamicSqlCompiler.compile(statement);
            MyBatisDynamicSqlCompileResult second = MyBatisDynamicSqlCompiler.compile(statement);
            assertInstanceOf(first, MyBatisDynamicSqlCompileResult.IndexNotReady.class);
            assertInstanceOf(second, MyBatisDynamicSqlCompileResult.IndexNotReady.class);
            assertNotSame(first, second);
        });

        MyBatisDynamicSqlCompileResult smart = MyBatisDynamicSqlCompiler.compile(statement);
        assertInstanceOf(smart, MyBatisDynamicSqlCompileResult.Compiled.class);
        assertSame(smart, MyBatisDynamicSqlCompiler.compile(statement));
    }

    public void testThousandIndependentConditionsHaveLinearNodeBudget() {
        int conditions = 1000;
        StringBuilder xml = new StringBuilder(
                "<mapper namespace=\"com.example.UserMapper\"><select id=\"find\">select 1");
        for (int index = 0; index < conditions; index++) {
            xml.append("<if test=\"p")
                    .append(index)
                    .append(" != null\"> and c")
                    .append(index)
                    .append(" = #{p")
                    .append(index)
                    .append("}</if>");
        }
        xml.append("</select></mapper>");
        XmlFile file = configure(xml.toString());

        MyBatisDynamicSqlProgram program = program(
                MyBatisDynamicSqlCompiler.compileUncached(statement(file)));

        assertEquals(conditions, count(program.root(), MyBatisIfNode.class));
        assertTrue("1000 个条件不得产生组合分支，实际节点：" + countAll(program.root()),
                countAll(program.root()) <= conditions * 2 + 2);
    }

    public void testDeterministicRandomTreesPreserveIrAndSourceMapInvariants() {
        Random random = new Random(3154L);
        for (int sample = 0; sample < 30; sample++) {
            ProgressManager.checkCanceled();
            StringBuilder body = new StringBuilder("select &lt; 100 ");
            int dynamicNodes = 60;
            for (int index = 0; index < dynamicNodes; index++) {
                appendRandomNode(body, random, sample, index);
            }
            XmlFile file = configure("<mapper namespace=\"com.example.UserMapper\">"
                    + "<select id=\"find\">" + body + "</select></mapper>");

            MyBatisDynamicSqlProgram program = program(
                    MyBatisDynamicSqlCompiler.compileUncached(statement(file)));

            assertEmpty(program.diagnostics());
            assertTrue(countAll(program.root()) <= dynamicNodes * 5 + 4);
            assertSourceMapsValid(program.root());
        }
    }

    public void testCancellationDuringLargeCompilationPropagates() throws Exception {
        StringBuilder xml = new StringBuilder(
                "<mapper namespace=\"com.example.UserMapper\"><select id=\"find\">");
        for (int index = 0; index < 2000; index++) {
            xml.append("<if test=\"p")
                    .append(index)
                    .append("\">value")
                    .append(index)
                    .append("</if>");
        }
        xml.append("</select></mapper>");
        XmlTag statement = statement(configure(xml.toString()));
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        CountDownLatch compilationStarted = new CountDownLatch(1);
        Thread canceller = Thread.ofPlatform().start(() -> {
            try {
                compilationStarted.await();
                indicator.cancel();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        compilationStarted.countDown();
                        return MyBatisDynamicSqlCompiler.compileUncached(statement);
                    },
                    indicator);
            fail("编译器必须在遍历大型动态树期间响应取消");
        } catch (ProcessCanceledException expected) {
            // 取消是平台正常控制流，编译器不得转成诊断或空结果。
        } finally {
            canceller.join(5000L);
        }
        assertFalse(canceller.isAlive());
    }

    private void appendRandomNode(
            StringBuilder body,
            Random random,
            int sample,
            int index) {
        switch (random.nextInt(5)) {
            case 0 -> body.append("<if test=\"p")
                    .append(index)
                    .append(" != null\"> c")
                    .append(sample)
                    .append('_')
                    .append(index)
                    .append(" = #{p")
                    .append(index)
                    .append("}</if>");
            case 1 -> body.append("<where><if test=\"enabled\">and enabled = 1</if></where>");
            case 2 -> body.append("<trim prefix=\"(\" suffix=\")\">x = 1</trim>");
            case 3 -> body.append("<foreach collection=\"ids\" separator=\",\">#{item}</foreach>");
            default -> body.append("<choose><when test=\"ok\">yes</when>"
                    + "<otherwise>no</otherwise></choose>");
        }
    }

    private void replace(PsiFile file, String current, String replacement) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        int start = document.getText().indexOf(current);
        assertTrue(start >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                document.replaceString(start, start + current.length(), replacement));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    private XmlFile configure(String text) {
        return (XmlFile) myFixture.configureByText("UserMapper.xml", text);
    }

    private static XmlTag statement(XmlFile file) {
        XmlTag mapper = file.getRootTag();
        assertNotNull(mapper);
        XmlTag statement = mapper.findFirstSubTag("select");
        assertNotNull(statement);
        return statement;
    }

    private static MyBatisDynamicSqlProgram program(MyBatisDynamicSqlCompileResult result) {
        return assertInstanceOf(result, MyBatisDynamicSqlCompileResult.Compiled.class).program();
    }

    private static String staticSql(MyBatisDynamicSqlCompileResult result) {
        return program(result).staticSql().orElseThrow().text();
    }

    private static int count(MyBatisDynamicSqlNode root, Class<?> type) {
        int result = 0;
        Deque<MyBatisDynamicSqlNode> remaining = new ArrayDeque<>();
        remaining.add(root);
        while (!remaining.isEmpty()) {
            ProgressManager.checkCanceled();
            MyBatisDynamicSqlNode node = remaining.removeFirst();
            if (type.isInstance(node)) {
                result++;
            }
            addChildren(node, remaining);
        }
        return result;
    }

    private static int countAll(MyBatisDynamicSqlNode root) {
        return count(root, MyBatisDynamicSqlNode.class);
    }

    private static void assertSourceMapsValid(MyBatisDynamicSqlNode root) {
        Deque<MyBatisDynamicSqlNode> remaining = new ArrayDeque<>();
        remaining.add(root);
        while (!remaining.isEmpty()) {
            ProgressManager.checkCanceled();
            MyBatisDynamicSqlNode node = remaining.removeFirst();
            if (node instanceof MyBatisSqlTextNode text) {
                MyBatisSourceMap map = text.content().sourceMap();
                assertEquals(text.content().text().length(), map.virtualLength());
                int cursor = 0;
                for (MyBatisSourceMapSegment segment : map.segments()) {
                    assertEquals(cursor, segment.virtualRange().startOffset());
                    cursor = segment.virtualRange().endOffset();
                }
                assertEquals(map.virtualLength(), cursor);
            }
            addChildren(node, remaining);
        }
    }

    private static void addChildren(
            MyBatisDynamicSqlNode node,
            Deque<MyBatisDynamicSqlNode> remaining) {
        switch (node) {
            case MyBatisSqlSequenceNode sequence -> remaining.addAll(sequence.children());
            case MyBatisIfNode conditional -> remaining.add(conditional.body());
            case MyBatisChooseNode choose -> {
                choose.branches().forEach(branch -> remaining.add(branch.body()));
                choose.otherwiseBranch().ifPresent(remaining::add);
            }
            case MyBatisTrimNode trim -> remaining.add(trim.body());
            case MyBatisForeachNode foreach -> remaining.add(foreach.body());
            case MyBatisIncludeNode include -> remaining.add(include.expandedBody());
            case MyBatisBindNode ignored -> {
                // bind 没有子节点。
            }
            case MyBatisSqlTextNode ignored -> {
                // 文本没有子节点。
            }
        }
    }

}
