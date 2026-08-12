package io.github.ns3154.mybatisassistant.dynamic;

import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 用版本化输入与完整快照锁定 S5 动态 SQL 编译契约。
 */
public final class MyBatisDynamicSqlGoldenTest extends BasePlatformTestCase {
    private static final String GOLDEN_ROOT = "/dynamic-sql-golden/v1/";
    private MyBatisAssistantSettings.SettingsState originalSettings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        originalSettings = MyBatisAssistantSettings.getInstance().getState();
        MyBatisAssistantSettings.SettingsState deterministicSettings =
                originalSettings.copyAndNormalize();
        deterministicSettings.uiLocale = "zh-CN";
        MyBatisAssistantSettings.getInstance().replace(deterministicSettings);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MyBatisAssistantSettings.getInstance().replace(originalSettings);
        } finally {
            super.tearDown();
        }
    }

    public void testControlFlowGolden() throws Exception {
        assertGolden("control-flow", List.of("find", "update"));
    }

    public void testIterationAndIncludeGolden() throws Exception {
        assertGolden("iteration-and-include", List.of("findBatch"));
    }

    public void testMappingAndDiagnosticsGolden() throws Exception {
        assertGolden("mapping-and-diagnostics", List.of("mapped", "invalid"));
    }

    private void assertGolden(String fixtureName, List<String> statementIds)
            throws IOException {
        String xml = readResource(fixtureName + ".xml");
        XmlFile file = (XmlFile) myFixture.configureByText(
                fixtureName + ".xml", xml);

        String first = compileSnapshot(fixtureName, file, statementIds);
        String second = compileSnapshot(fixtureName, file, statementIds);
        assertEquals("同一 PSI 的完整编译快照必须确定", first, second);
        assertEquals(
                "版本化动态 SQL 黄金文件发生变化：" + fixtureName,
                readResource(fixtureName + ".expected"),
                first);
    }

    private static String compileSnapshot(
            String fixtureName,
            XmlFile file,
            List<String> statementIds) {
        XmlTag mapper = file.getRootTag();
        assertNotNull(mapper);
        SnapshotRenderer renderer = new SnapshotRenderer(fixtureName);
        for (String statementId : statementIds) {
            XmlTag statement = findStatement(mapper, statementId);
            renderer.renderStatement(
                    statementId,
                    MyBatisDynamicSqlCompiler.compile(statement));
        }
        return renderer.finish();
    }

    private static XmlTag findStatement(XmlTag mapper, String statementId) {
        for (XmlTag child : mapper.getSubTags()) {
            if (statementId.equals(child.getAttributeValue("id"))) {
                return child;
            }
        }
        fail("黄金输入缺少 statement：" + statementId);
        throw new AssertionError("unreachable");
    }

    private String readResource(String fileName) throws IOException {
        String path = GOLDEN_ROOT + fileName;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull("缺少黄金资源：" + path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }

    /**
     * 只序列化编译器公开模型，不重新实现任何动态 SQL 编译规则。
     */
    private static final class SnapshotRenderer {
        private final StringBuilder output = new StringBuilder();

        private SnapshotRenderer(String fixtureName) {
            line(0, "format=dynamic-sql-golden-v1");
            line(0, "fixture=" + quote(fixtureName));
        }

        private void renderStatement(
                String statementId,
                MyBatisDynamicSqlCompileResult result) {
            line(0, "statement=" + quote(statementId));
            switch (result) {
                case MyBatisDynamicSqlCompileResult.Compiled compiled -> {
                    line(1, "result=COMPILED");
                    renderProgram(compiled.program(), 1);
                }
                case MyBatisDynamicSqlCompileResult.IndexNotReady ignored ->
                        line(1, "result=INDEX_NOT_READY");
                case MyBatisDynamicSqlCompileResult.SourceInvalid ignored ->
                        line(1, "result=SOURCE_INVALID");
                case MyBatisDynamicSqlCompileResult.UnsupportedSource ignored ->
                        line(1, "result=UNSUPPORTED_SOURCE");
            }
        }

        private void renderProgram(MyBatisDynamicSqlProgram program, int depth) {
            line(depth, "diagnostics=" + program.diagnostics().size());
            for (MyBatisDynamicSqlDiagnostic diagnostic : program.diagnostics()) {
                line(depth + 1,
                        "diagnostic code=" + diagnostic.code()
                                + " message=" + quote(diagnostic.message())
                                + " source=" + source(diagnostic.sourceRange()));
            }
            line(depth, "root:");
            renderNode(program.root(), depth + 1);
            if (program.staticSql().isPresent()) {
                line(depth, "static-sql:");
                renderMappedText(program.staticSql().orElseThrow(), depth + 1);
            } else {
                line(depth, "static-sql=<dynamic>");
            }
        }

        private void renderNode(MyBatisDynamicSqlNode node, int depth) {
            switch (node) {
                case MyBatisSqlSequenceNode sequence -> {
                    line(depth, "sequence children=" + sequence.children().size());
                    for (MyBatisDynamicSqlNode child : sequence.children()) {
                        renderNode(child, depth + 1);
                    }
                }
                case MyBatisSqlTextNode text -> {
                    line(depth, "text:");
                    renderMappedText(text.content(), depth + 1);
                }
                case MyBatisIfNode conditional -> {
                    line(depth, "if source=" + source(conditional.sourceRange()));
                    renderExpression("condition", conditional.condition(), depth + 1);
                    line(depth + 1, "body:");
                    renderNode(conditional.body(), depth + 2);
                }
                case MyBatisChooseNode choose -> {
                    line(depth,
                            "choose branches=" + choose.branches().size()
                                    + " otherwise=" + choose.otherwiseBranch().isPresent()
                                    + " source=" + source(choose.sourceRange()));
                    int branchIndex = 0;
                    for (MyBatisWhenBranch branch : choose.branches()) {
                        line(depth + 1,
                                "when index=" + branchIndex
                                        + " source=" + source(branch.sourceRange()));
                        renderExpression("condition", branch.condition(), depth + 2);
                        line(depth + 2, "body:");
                        renderNode(branch.body(), depth + 3);
                        branchIndex++;
                    }
                    choose.otherwiseBranch().ifPresent(otherwise -> {
                        line(depth + 1, "otherwise:");
                        renderNode(otherwise, depth + 2);
                    });
                }
                case MyBatisTrimNode trim -> {
                    line(depth,
                            "trim kind=" + trim.kind()
                                    + " prefix=" + quote(trim.prefix())
                                    + " suffix=" + quote(trim.suffix())
                                    + " prefix-overrides=" + quote(trim.prefixOverrides())
                                    + " suffix-overrides=" + quote(trim.suffixOverrides())
                                    + " source=" + source(trim.sourceRange()));
                    line(depth + 1, "body:");
                    renderNode(trim.body(), depth + 2);
                }
                case MyBatisForeachNode foreach -> {
                    line(depth,
                            "foreach open=" + quote(foreach.open())
                                    + " close=" + quote(foreach.close())
                                    + " separator=" + quote(foreach.separator())
                                    + " nullable=" + foreach.nullable()
                                    .map(String::valueOf).orElse("<unset>")
                                    + " source=" + source(foreach.sourceRange()));
                    renderExpression("collection", foreach.collection(), depth + 1);
                    line(depth + 1, "bindings=" + foreach.scopedBindings().size());
                    for (MyBatisDynamicSqlBinding binding : foreach.scopedBindings()) {
                        renderBinding(binding, depth + 2);
                    }
                    line(depth + 1, "body:");
                    renderNode(foreach.body(), depth + 2);
                }
                case MyBatisBindNode bind -> {
                    line(depth, "bind source=" + source(bind.sourceRange()));
                    renderBinding(bind.binding(), depth + 1);
                }
                case MyBatisIncludeNode include -> {
                    line(depth,
                            "include namespace=" + quote(include.namespace())
                                    + " fragment=" + quote(include.fragmentId())
                                    + " source=" + source(include.includeSourceRange())
                                    + " fragment-source="
                                    + source(include.fragmentSourceRange()));
                    line(depth + 1, "properties=" + include.properties().size());
                    for (MyBatisDynamicSqlBinding property : include.properties()) {
                        renderBinding(property, depth + 2);
                    }
                    line(depth + 1, "expanded-body:");
                    renderNode(include.expandedBody(), depth + 2);
                }
            }
        }

        private void renderBinding(MyBatisDynamicSqlBinding binding, int depth) {
            line(depth,
                    "binding name=" + quote(binding.name())
                            + " kind=" + binding.kind()
                            + " source=" + source(binding.sourceRange()));
            if (binding.initializer().isPresent()) {
                renderExpression(
                        "initializer", binding.initializer().orElseThrow(), depth + 1);
            } else {
                line(depth + 1, "initializer=<none>");
            }
        }

        private void renderExpression(
                String label,
                MyBatisDynamicSqlExpression expression,
                int depth) {
            line(depth,
                    label + " text=" + quote(expression.text())
                            + " source=" + source(expression.sourceRange()));
        }

        private void renderMappedText(MyBatisMappedText mappedText, int depth) {
            line(depth, "value=" + quote(mappedText.text()));
            MyBatisSourceMap sourceMap = mappedText.sourceMap();
            line(depth,
                    "source-map virtual-length=" + sourceMap.virtualLength()
                            + " segments=" + sourceMap.segments().size());
            for (MyBatisSourceMapSegment segment : sourceMap.segments()) {
                line(depth + 1,
                        "segment kind=" + segment.kind()
                                + " virtual=" + range(segment.virtualRange())
                                + " source=" + source(segment.sourceRange()));
            }
        }

        private String finish() {
            return output.toString();
        }

        private void line(int depth, String value) {
            output.append("  ".repeat(depth)).append(value).append('\n');
        }

        private static String source(MyBatisSourceRange sourceRange) {
            String fileUrl = sourceRange.fileUrl();
            int slash = Math.max(fileUrl.lastIndexOf('/'), fileUrl.lastIndexOf('\\'));
            String fileName = slash >= 0 ? fileUrl.substring(slash + 1) : fileUrl;
            return fileName + ":" + range(sourceRange.range());
        }

        private static String range(MyBatisTextRange range) {
            return "[" + range.startOffset() + "," + range.endOffset() + ")";
        }

        private static String quote(String value) {
            StringBuilder quoted = new StringBuilder(value.length() + 2);
            quoted.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '\\' -> quoted.append("\\\\");
                    case '"' -> quoted.append("\\\"");
                    case '\r' -> quoted.append("\\r");
                    case '\n' -> quoted.append("\\n");
                    case '\t' -> quoted.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            quoted.append(String.format("\\u%04x", (int) character));
                        } else {
                            quoted.append(character);
                        }
                    }
                }
            }
            return quoted.append('"').toString();
        }
    }
}
