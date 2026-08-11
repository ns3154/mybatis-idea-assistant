package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 使用纯解析 AST 迭代构建 IntelliJ 复合 PSI，避免深链递归溢出。
 */
public final class MyBatisOgnlPsiParser implements PsiParser {
    @Override
    public @NotNull ASTNode parse(
            @NotNull IElementType root,
            @NotNull PsiBuilder builder) {
        MyBatisOgnlParseResult parsed = MyBatisOgnlParser.parse(builder.getOriginalText());
        List<MyBatisOgnlDiagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        diagnostics.sort(Comparator.comparingInt(diagnostic ->
                diagnostic.range().startOffset()));
        Cursor cursor = new Cursor(builder, diagnostics);
        PsiBuilder.Marker file = builder.mark();
        Deque<Event> events = new ArrayDeque<>();
        events.push(new OpenEvent(parsed.root()));
        while (!events.isEmpty()) {
            ProgressManager.checkCanceled();
            Event event = events.pop();
            if (event instanceof OpenEvent open) {
                MyBatisOgnlExpression expression = open.expression();
                cursor.advanceUntil(expression.range().startOffset());
                PsiBuilder.Marker marker = builder.mark();
                events.push(new CloseEvent(
                        marker,
                        MyBatisOgnlTypes.expression(expression),
                        expression.range().endOffset()));
                List<MyBatisOgnlExpression> children = children(expression);
                for (int index = children.size() - 1; index >= 0; index--) {
                    events.push(new OpenEvent(children.get(index)));
                }
            } else if (event instanceof CloseEvent close) {
                cursor.advanceUntil(close.endOffset());
                close.marker().done(close.type());
            }
        }
        cursor.advanceUntil(Integer.MAX_VALUE);
        cursor.emitRemainingDiagnostics();
        file.done(root);
        return builder.getTreeBuilt();
    }

    private static @NotNull List<MyBatisOgnlExpression> children(
            @NotNull MyBatisOgnlExpression expression) {
        List<MyBatisOgnlExpression> result = new ArrayList<>();
        switch (expression) {
            case MyBatisOgnlExpression.Unary unary -> result.add(unary.operand());
            case MyBatisOgnlExpression.Binary binary -> {
                result.add(binary.left());
                result.add(binary.right());
            }
            case MyBatisOgnlExpression.Conditional conditional -> {
                result.add(conditional.condition());
                result.add(conditional.whenTrue());
                result.add(conditional.whenFalse());
            }
            case MyBatisOgnlExpression.Property property -> result.add(property.target());
            case MyBatisOgnlExpression.MethodCall method -> {
                method.target().ifPresent(result::add);
                result.addAll(method.arguments());
            }
            case MyBatisOgnlExpression.Index index -> {
                result.add(index.target());
                result.add(index.index());
            }
            case MyBatisOgnlExpression.StaticMember member ->
                    member.arguments().ifPresent(result::addAll);
            case MyBatisOgnlExpression.TypeLiteral ignored -> {
                // 类型字面量没有子表达式。
            }
            case MyBatisOgnlExpression.ListLiteral list -> result.addAll(list.elements());
            case MyBatisOgnlExpression.MapLiteral map -> map.entries().forEach(entry -> {
                result.add(entry.key());
                result.add(entry.value());
            });
            case MyBatisOgnlExpression.Group group -> result.add(group.expression());
            case MyBatisOgnlExpression.Literal ignored -> {
                // 叶子节点没有子表达式。
            }
            case MyBatisOgnlExpression.Name ignored -> {
                // 叶子节点没有子表达式。
            }
            case MyBatisOgnlExpression.Error ignored -> {
                // 错误节点没有子表达式。
            }
        }
        result.sort(Comparator.comparingInt(child -> child.range().startOffset()));
        return result;
    }

    private sealed interface Event {
    }

    private record OpenEvent(@NotNull MyBatisOgnlExpression expression) implements Event {
    }

    private record CloseEvent(
            @NotNull PsiBuilder.Marker marker,
            @NotNull IElementType type,
            int endOffset) implements Event {
    }

    private static final class Cursor {
        private final PsiBuilder builder;
        private final List<MyBatisOgnlDiagnostic> diagnostics;
        private int diagnosticIndex;

        private Cursor(
                @NotNull PsiBuilder builder,
                @NotNull List<MyBatisOgnlDiagnostic> diagnostics) {
            this.builder = builder;
            this.diagnostics = diagnostics;
        }

        private void advanceUntil(int offset) {
            while (!builder.eof() && builder.getCurrentOffset() < offset) {
                ProgressManager.checkCanceled();
                emitDiagnosticsAtOrBefore(builder.getCurrentOffset());
                builder.advanceLexer();
            }
            emitDiagnosticsAtOrBefore(Math.min(offset, builder.getCurrentOffset()));
        }

        private void emitRemainingDiagnostics() {
            while (diagnosticIndex < diagnostics.size()) {
                builder.error(diagnostics.get(diagnosticIndex++).message());
            }
        }

        private void emitDiagnosticsAtOrBefore(int offset) {
            while (diagnosticIndex < diagnostics.size()
                    && diagnostics.get(diagnosticIndex).range().startOffset() <= offset) {
                builder.error(diagnostics.get(diagnosticIndex++).message());
            }
        }
    }
}
