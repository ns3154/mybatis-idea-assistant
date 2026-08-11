package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

public final class MyBatisOgnlParserTest extends BasePlatformTestCase {
    public void testLexerRecognizesKeywordsOperatorsLiteralsAndExactRanges() {
        String source = "name != null and score gte 10 || enabled == true";
        MyBatisOgnlLexResult result = MyBatisOgnlLexer.lex(
                source);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(List.of(
                        MyBatisOgnlTokenKind.IDENTIFIER,
                        MyBatisOgnlTokenKind.NOT_EQ,
                        MyBatisOgnlTokenKind.NULL,
                        MyBatisOgnlTokenKind.AND,
                        MyBatisOgnlTokenKind.IDENTIFIER,
                        MyBatisOgnlTokenKind.GTE,
                        MyBatisOgnlTokenKind.NUMBER,
                        MyBatisOgnlTokenKind.OR_OR,
                        MyBatisOgnlTokenKind.IDENTIFIER,
                        MyBatisOgnlTokenKind.EQ_EQ,
                        MyBatisOgnlTokenKind.TRUE,
                        MyBatisOgnlTokenKind.EOF),
                result.tokens().stream().map(MyBatisOgnlToken::kind).toList());
        assertEquals(new MyBatisOgnlRange(0, 4), result.tokens().getFirst().range());
        assertEquals("true", result.tokens().get(10).text());
        assertEquals(new MyBatisOgnlRange(source.length(), source.length()),
                result.tokens().getLast().range());
    }

    public void testOperatorPrecedenceAndUnaryTree() {
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(
                "a + b * c == 10 and !empty or fallback");

        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        MyBatisOgnlExpression.Binary or = assertBinary(
                result.root(), MyBatisOgnlOperator.OR);
        MyBatisOgnlExpression.Binary and = assertBinary(
                or.left(), MyBatisOgnlOperator.AND);
        MyBatisOgnlExpression.Binary equal = assertBinary(
                and.left(), MyBatisOgnlOperator.EQUAL);
        MyBatisOgnlExpression.Binary add = assertBinary(
                equal.left(), MyBatisOgnlOperator.ADD);
        assertBinary(add.right(), MyBatisOgnlOperator.MULTIPLY);
        MyBatisOgnlExpression.Unary not = assertInstanceOf(
                and.right(), MyBatisOgnlExpression.Unary.class);
        assertEquals(MyBatisOgnlOperator.NOT, not.operator());
    }

    public void testPropertyMethodIndexAndContextVariableChains() {
        MyBatisOgnlParseResult rootChain = MyBatisOgnlParser.parse(
                "_parameter['name'].trim().isEmpty()");
        assertTrue(rootChain.diagnostics().toString(), rootChain.diagnostics().isEmpty());
        MyBatisOgnlExpression.MethodCall isEmpty = assertInstanceOf(
                rootChain.root(), MyBatisOgnlExpression.MethodCall.class);
        assertEquals("isEmpty", isEmpty.name());
        MyBatisOgnlExpression.MethodCall trim = assertInstanceOf(
                isEmpty.target().orElseThrow(), MyBatisOgnlExpression.MethodCall.class);
        assertEquals("trim", trim.name());
        MyBatisOgnlExpression.Index index = assertInstanceOf(
                trim.target().orElseThrow(), MyBatisOgnlExpression.Index.class);
        assertEquals("_parameter", assertInstanceOf(
                index.target(), MyBatisOgnlExpression.Name.class).name());
        assertEquals("name", assertInstanceOf(
                index.index(), MyBatisOgnlExpression.Literal.class).value());

        MyBatisOgnlParseResult contextChain = MyBatisOgnlParser.parse("#context.user.name");
        assertTrue(contextChain.diagnostics().isEmpty());
        MyBatisOgnlExpression.Property name = assertInstanceOf(
                contextChain.root(), MyBatisOgnlExpression.Property.class);
        MyBatisOgnlExpression.Property user = assertInstanceOf(
                name.target(), MyBatisOgnlExpression.Property.class);
        MyBatisOgnlExpression.Name context = assertInstanceOf(
                user.target(), MyBatisOgnlExpression.Name.class);
        assertEquals(MyBatisOgnlNameKind.CONTEXT, context.kind());
        assertEquals("context", context.name());
    }

    public void testConditionalListMapInAndNotIn() {
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(
                "id not in {1, 2} ? #{'yes': true} : #{'no': false}");

        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        MyBatisOgnlExpression.Conditional conditional = assertInstanceOf(
                result.root(), MyBatisOgnlExpression.Conditional.class);
        MyBatisOgnlExpression.Binary condition = assertBinary(
                conditional.condition(), MyBatisOgnlOperator.NOT_IN);
        assertEquals(2, assertInstanceOf(
                condition.right(), MyBatisOgnlExpression.ListLiteral.class).elements().size());
        assertEquals(1, assertInstanceOf(
                conditional.whenTrue(), MyBatisOgnlExpression.MapLiteral.class).entries().size());
        assertEquals(1, assertInstanceOf(
                conditional.whenFalse(), MyBatisOgnlExpression.MapLiteral.class).entries().size());
    }

    public void testStaticFieldsMethodsAndClassRanges() {
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(
                "@java.lang.Math@max(1, 2) > @java.lang.Integer@MAX_VALUE");

        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        MyBatisOgnlExpression.Binary greater = assertBinary(
                result.root(), MyBatisOgnlOperator.GREATER);
        MyBatisOgnlExpression.StaticMember method = assertInstanceOf(
                greater.left(), MyBatisOgnlExpression.StaticMember.class);
        assertEquals("java.lang.Math", method.className());
        assertEquals("max", method.memberName());
        assertEquals(2, method.arguments().orElseThrow().size());
        assertEquals(new MyBatisOgnlRange(1, 15), method.classRange());
        MyBatisOgnlExpression.StaticMember field = assertInstanceOf(
                greater.right(), MyBatisOgnlExpression.StaticMember.class);
        assertEquals("java.lang.Integer", field.className());
        assertEquals("MAX_VALUE", field.memberName());
        assertTrue(field.arguments().isEmpty());
    }

    public void testInstanceOfUsesQualifiedTypeLiteralInsteadOfPropertyChain() {
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(
                "value instanceof java.util.Collection and enabled");

        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        MyBatisOgnlExpression.Binary and = assertBinary(
                result.root(),
                MyBatisOgnlOperator.AND);
        MyBatisOgnlExpression.Binary instanceOf = assertBinary(
                and.left(),
                MyBatisOgnlOperator.INSTANCE_OF);
        MyBatisOgnlExpression.TypeLiteral literal = assertInstanceOf(
                instanceOf.right(),
                MyBatisOgnlExpression.TypeLiteral.class);
        assertEquals("java.util.Collection", literal.qualifiedName());
        assertEquals(new MyBatisOgnlRange(17, 37), literal.nameRange());
    }

    public void testCharacterStringEscapesAndInvalidLiteralDiagnostics() {
        assertEquals(MyBatisOgnlLiteralKind.CHARACTER, literal("'a'").kind());
        assertEquals(MyBatisOgnlLiteralKind.CHARACTER, literal("'😀'").kind());
        assertEquals(MyBatisOgnlLiteralKind.STRING, literal("'ab'").kind());
        MyBatisOgnlExpression.Literal escaped = literal("\"line\\nnext\"");
        assertEquals("line\nnext", escaped.value());

        MyBatisOgnlParseResult invalidEscape = MyBatisOgnlParser.parse("'\\q'");
        assertTrue(invalidEscape.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisOgnlDiagnosticCode.INVALID_ESCAPE));
        MyBatisOgnlParseResult invalidNumber = MyBatisOgnlParser.parse("1..2");
        assertTrue(invalidNumber.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisOgnlDiagnosticCode.INVALID_NUMBER));
    }

    public void testIncompleteInputRecoversWithTypedDiagnostics() {
        List<String> inputs = List.of(
                "user..name",
                "items[0",
                "method(1,",
                "condition ? value",
                "#{'key' value}",
                "@java.lang.Math@",
                "'unterminated");
        for (String input : inputs) {
            MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(input);
            assertNotNull(result.root());
            assertFalse("输入应产生诊断：" + input, result.diagnostics().isEmpty());
            assertEquals(MyBatisOgnlTokenKind.EOF, result.tokens().getLast().kind());
        }
    }

    public void testThousandsOfUnaryAndPropertyTokensRemainLinearAndStackSafe() {
        String unarySource = "!".repeat(4000) + "enabled";
        MyBatisOgnlParseResult unary = MyBatisOgnlParser.parse(unarySource);
        assertTrue(unary.diagnostics().toString(), unary.diagnostics().isEmpty());
        assertEquals(4001, countNodes(unary.root()));

        StringBuilder chain = new StringBuilder("root");
        int properties = 2000;
        for (int index = 0; index < properties; index++) {
            chain.append(".p").append(index);
        }
        MyBatisOgnlParseResult property = MyBatisOgnlParser.parse(chain);
        assertTrue(property.diagnostics().toString(), property.diagnostics().isEmpty());
        assertEquals(properties + 1, countNodes(property.root()));
    }

    public void testDeepNestingStopsWithStableDiagnosticInsteadOfOverflow() {
        String source = "(".repeat(600) + "value" + ")".repeat(600);
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(source);

        assertNotNull(result.root());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == MyBatisOgnlDiagnosticCode.MAXIMUM_NESTING_EXCEEDED));
    }

    public void testDeterministicRandomUnicodeNeverCrashesOrLosesEof() {
        Random random = new Random(6314L);
        String alphabet = "abcXYZ012_ +-*/%!?<>=&|().,[]{}@#:'\"\\\t\n中文😀";
        for (int sample = 0; sample < 1000; sample++) {
            StringBuilder source = new StringBuilder();
            int length = random.nextInt(160);
            for (int index = 0; index < length; index++) {
                int offset = random.nextInt(alphabet.length());
                source.append(alphabet.charAt(offset));
            }
            MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(source);
            assertNotNull(result.root());
            assertEquals(MyBatisOgnlTokenKind.EOF, result.tokens().getLast().kind());
            assertTrue(result.tokens().size() <= source.length() + 1);
        }
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();
        try {
            ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                return MyBatisOgnlParser.parse("value and other");
            }, indicator);
            fail("取消后的 OGNL 解析必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是平台控制流，解析器不得转成语法诊断。
        }
    }

    private static MyBatisOgnlExpression.Literal literal(String source) {
        MyBatisOgnlParseResult result = MyBatisOgnlParser.parse(source);
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        return assertInstanceOf(result.root(), MyBatisOgnlExpression.Literal.class);
    }

    private static MyBatisOgnlExpression.Binary assertBinary(
            MyBatisOgnlExpression expression,
            MyBatisOgnlOperator operator) {
        MyBatisOgnlExpression.Binary binary = assertInstanceOf(
                expression, MyBatisOgnlExpression.Binary.class);
        assertEquals(operator, binary.operator());
        return binary;
    }

    private static int countNodes(MyBatisOgnlExpression root) {
        int count = 0;
        Deque<MyBatisOgnlExpression> remaining = new ArrayDeque<>();
        remaining.add(root);
        while (!remaining.isEmpty()) {
            MyBatisOgnlExpression expression = remaining.removeFirst();
            count++;
            switch (expression) {
                case MyBatisOgnlExpression.Unary unary -> remaining.add(unary.operand());
                case MyBatisOgnlExpression.Binary binary -> {
                    remaining.add(binary.left());
                    remaining.add(binary.right());
                }
                case MyBatisOgnlExpression.Conditional conditional -> {
                    remaining.add(conditional.condition());
                    remaining.add(conditional.whenTrue());
                    remaining.add(conditional.whenFalse());
                }
                case MyBatisOgnlExpression.Property property -> remaining.add(property.target());
                case MyBatisOgnlExpression.MethodCall method -> {
                    method.target().ifPresent(remaining::add);
                    remaining.addAll(method.arguments());
                }
                case MyBatisOgnlExpression.Index index -> {
                    remaining.add(index.target());
                    remaining.add(index.index());
                }
                case MyBatisOgnlExpression.StaticMember member ->
                        member.arguments().ifPresent(remaining::addAll);
                case MyBatisOgnlExpression.TypeLiteral ignored -> {
                    // 类型字面量没有子节点。
                }
                case MyBatisOgnlExpression.ListLiteral list -> remaining.addAll(list.elements());
                case MyBatisOgnlExpression.MapLiteral map -> map.entries().forEach(entry -> {
                    remaining.add(entry.key());
                    remaining.add(entry.value());
                });
                case MyBatisOgnlExpression.Group group -> remaining.add(group.expression());
                case MyBatisOgnlExpression.Literal ignored -> {
                    // 字面量没有子节点。
                }
                case MyBatisOgnlExpression.Name ignored -> {
                    // 名称没有子节点。
                }
                case MyBatisOgnlExpression.Error ignored -> {
                    // 错误节点没有子节点。
                }
            }
        }
        return count;
    }
}
