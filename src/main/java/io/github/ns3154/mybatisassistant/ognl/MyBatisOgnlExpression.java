package io.github.ns3154.mybatisassistant.ognl;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 不持有 PSI、可用于随机测试和平台 PSI 构建的不可变 OGNL AST。
 */
public sealed interface MyBatisOgnlExpression {
    @NotNull MyBatisOgnlRange range();

    record Literal(
            @NotNull String rawText,
            @NotNull String value,
            @NotNull MyBatisOgnlLiteralKind kind,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Name(
            @NotNull String name,
            @NotNull MyBatisOgnlNameKind kind,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Unary(
            @NotNull MyBatisOgnlOperator operator,
            @NotNull MyBatisOgnlExpression operand,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Binary(
            @NotNull MyBatisOgnlOperator operator,
            @NotNull MyBatisOgnlExpression left,
            @NotNull MyBatisOgnlExpression right,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Conditional(
            @NotNull MyBatisOgnlExpression condition,
            @NotNull MyBatisOgnlExpression whenTrue,
            @NotNull MyBatisOgnlExpression whenFalse,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Property(
            @NotNull MyBatisOgnlExpression target,
            @NotNull String name,
            @NotNull MyBatisOgnlRange nameRange,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record MethodCall(
            @NotNull Optional<MyBatisOgnlExpression> target,
            @NotNull String name,
            @NotNull MyBatisOgnlRange nameRange,
            @NotNull List<MyBatisOgnlExpression> arguments,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
        public MethodCall {
            arguments = List.copyOf(arguments);
        }
    }

    record Index(
            @NotNull MyBatisOgnlExpression target,
            @NotNull MyBatisOgnlExpression index,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record StaticMember(
            @NotNull String className,
            @NotNull MyBatisOgnlRange classRange,
            @NotNull String memberName,
            @NotNull MyBatisOgnlRange memberRange,
            @NotNull Optional<List<MyBatisOgnlExpression>> arguments,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
        public StaticMember {
            arguments = arguments.map(List::copyOf);
        }
    }

    record TypeLiteral(
            @NotNull String qualifiedName,
            @NotNull MyBatisOgnlRange nameRange,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record ListLiteral(
            @NotNull List<MyBatisOgnlExpression> elements,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    record MapEntry(
            @NotNull MyBatisOgnlExpression key,
            @NotNull MyBatisOgnlExpression value,
            @NotNull MyBatisOgnlRange range) {
    }

    record MapLiteral(
            @NotNull List<MapEntry> entries,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
        public MapLiteral {
            entries = List.copyOf(entries);
        }
    }

    record Group(
            @NotNull MyBatisOgnlExpression expression,
            @NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }

    record Error(@NotNull MyBatisOgnlRange range) implements MyBatisOgnlExpression {
    }
}
