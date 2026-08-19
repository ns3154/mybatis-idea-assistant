package io.github.ns3154.mybatisassistant.sql;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisChooseNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlExpression;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlProgram;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisForeachNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisIfNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisMappedText;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapBuilder;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceMapKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSourceRange;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSqlSequenceNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisSqlTextNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTrimKind;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTrimNode;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisWhenBranch;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

public final class MyBatisVirtualSqlBuilderTest extends BasePlatformTestCase {
    private static final String FILE_URL = "file:///project/UserMapper.xml";

    public void testNormalizesParameterAndDynamicPlaceholdersWithExactSourceRanges() {
        String source = "select * from ${table} where id = #{user.id}";
        MyBatisVirtualSql sql = build(text(source, 100));

        assertEquals(
                "select * from __mybatis_dynamic__ where id = ?",
                sql.mappedText().text());
        assertFalse(sql.representativeOnly());
        assertEmpty(sql.diagnostics());
        assertReplacementRange(
                sql,
                "__mybatis_dynamic__",
                100 + source.indexOf("${table}"),
                "${table}".length());
        assertReplacementRange(
                sql,
                "?",
                100 + source.indexOf("#{user.id}"),
                "#{user.id}".length());
    }

    public void testWhereForeachAndIfProduceOneBoundedRepresentative() {
        MyBatisTrimNode where = new MyBatisTrimNode(
                MyBatisTrimKind.WHERE,
                "",
                "",
                "",
                "",
                new MyBatisIfNode(
                        expression("name != null", 30),
                        text("  AND name = #{name}  ", 60),
                        range(20, 50)),
                range(10, 90));
        MyBatisForeachNode foreach = new MyBatisForeachNode(
                expression("ids", 100),
                List.of(),
                "(",
                ")",
                ",",
                Optional.empty(),
                text("#{id}", 120),
                range(95, 140));
        MyBatisSqlSequenceNode root = new MyBatisSqlSequenceNode(List.of(
                text("select * from users ", 0),
                where,
                text(" and id in ", 150),
                foreach));

        MyBatisVirtualSql sql = build(root);

        assertEquals("select * from users WHERE name = ? and id in (?)", sql.mappedText().text());
        assertTrue(sql.representativeOnly());
        assertEquals(sql.mappedText().text().length(), sql.mappedText().sourceMap().virtualLength());
    }

    public void testChooseUsesFirstBranchAndReportsCollapsedAlternatives() {
        MyBatisChooseNode choose = new MyBatisChooseNode(
                List.of(
                        new MyBatisWhenBranch(
                                expression("kind == 1", 20),
                                text("type = 1", 40),
                                range(10, 50)),
                        new MyBatisWhenBranch(
                                expression("kind == 2", 60),
                                text("type = 2", 80),
                                range(55, 90))),
                Optional.of(text("type is null", 100)),
                range(5, 120));

        MyBatisVirtualSql sql = build(choose);

        assertEquals("type = 1", sql.mappedText().text());
        assertTrue(sql.representativeOnly());
        assertEquals(1, sql.diagnostics().size());
        assertEquals(
                MyBatisVirtualSqlDiagnosticCode.CHOOSE_BRANCHES_COLLAPSED,
                sql.diagnostics().getFirst().code());
    }

    public void testPreservesDecodedEntityMappingAndDiagnosesMalformedPlaceholder() {
        MyBatisSourceMapBuilder source = new MyBatisSourceMapBuilder();
        source.appendExact("select ", FILE_URL, 0);
        source.appendDecoded("<", FILE_URL, new MyBatisTextRange(7, 11));
        source.appendExact(" #{missing", FILE_URL, 11);

        MyBatisVirtualSql sql = build(new MyBatisSqlTextNode(source.build()));

        assertEquals("select < #{missing", sql.mappedText().text());
        assertEquals(1, sql.diagnostics().size());
        assertEquals(
                MyBatisVirtualSqlDiagnosticCode.MALFORMED_PARAMETER_PLACEHOLDER,
                sql.diagnostics().getFirst().code());
        int malformed = sql.mappedText().text().indexOf("#{missing");
        assertEquals(
                MyBatisSourceMapKind.DECODED,
                sql.mappedText().sourceMap()
                        .sourceMappings(new MyBatisTextRange(
                                malformed,
                                sql.mappedText().text().length()))
                        .getFirst()
                        .kind());
        int entity = sql.mappedText().text().indexOf('<');
        assertEquals(
                MyBatisSourceMapKind.DECODED,
                sql.mappedText().sourceMap()
                        .sourceMappings(new MyBatisTextRange(entity, entity + 1))
                        .getFirst()
                        .kind());
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                return build(text("select #{id}", 0));
            }, indicator);
            fail("取消后的虚拟 SQL 构建必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台控制流，构建器不得转成普通诊断。
        }
    }

    public void testThousandChooseBranchesStillProduceOneBoundedPath() {
        List<MyBatisWhenBranch> branches = IntStream.range(0, 1_000)
                .mapToObj(index -> new MyBatisWhenBranch(
                        expression("kind == " + index, 10 + index * 20),
                        text("value = " + index, 20 + index * 20),
                        range(10 + index * 20, 30 + index * 20)))
                .toList();

        MyBatisVirtualSql sql = build(new MyBatisChooseNode(
                branches,
                Optional.empty(),
                range(0, 30_000)));

        assertEquals("value = 0", sql.mappedText().text());
        assertEquals(1, sql.diagnostics().size());
        assertTrue(sql.representativeOnly());
    }

    private static void assertReplacementRange(
            MyBatisVirtualSql sql,
            String replacement,
            int sourceStart,
            int sourceLength) {
        int virtualStart = sql.mappedText().text().indexOf(replacement);
        var mapping = sql.mappedText().sourceMap()
                .sourceMappings(new MyBatisTextRange(
                        virtualStart,
                        virtualStart + replacement.length()))
                .getFirst();
        assertEquals(MyBatisSourceMapKind.DECODED, mapping.kind());
        assertEquals(
                new MyBatisTextRange(sourceStart, sourceStart + sourceLength),
                mapping.sourceRange().range());
    }

    private static MyBatisVirtualSql build(io.github.ns3154.mybatisassistant.dynamic.MyBatisDynamicSqlNode root) {
        return MyBatisVirtualSqlBuilder.build(new MyBatisDynamicSqlProgram(
                root,
                List.of(),
                Optional.empty()));
    }

    private static MyBatisSqlTextNode text(String value, int sourceStart) {
        MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
        builder.appendExact(value, FILE_URL, sourceStart);
        return new MyBatisSqlTextNode(builder.build());
    }

    private static MyBatisDynamicSqlExpression expression(String text, int sourceStart) {
        return new MyBatisDynamicSqlExpression(
                text,
                range(sourceStart, sourceStart + text.length()));
    }

    private static MyBatisSourceRange range(int start, int end) {
        return new MyBatisSourceRange(FILE_URL, new MyBatisTextRange(start, end));
    }
}
