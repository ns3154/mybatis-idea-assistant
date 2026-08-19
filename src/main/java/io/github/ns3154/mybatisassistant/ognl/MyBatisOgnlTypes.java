package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * OGNL token 与复合 PSI 元素类型注册表。
 */
public final class MyBatisOgnlTypes {
    public static final IElementType LITERAL = element("LITERAL");
    public static final IElementType NAME = element("NAME");
    public static final IElementType UNARY = element("UNARY");
    public static final IElementType BINARY = element("BINARY");
    public static final IElementType CONDITIONAL = element("CONDITIONAL");
    public static final IElementType PROPERTY = element("PROPERTY");
    public static final IElementType METHOD_CALL = element("METHOD_CALL");
    public static final IElementType INDEX = element("INDEX");
    public static final IElementType STATIC_MEMBER = element("STATIC_MEMBER");
    public static final IElementType TYPE_LITERAL = element("TYPE_LITERAL");
    public static final IElementType LIST_LITERAL = element("LIST_LITERAL");
    public static final IElementType MAP_LITERAL = element("MAP_LITERAL");
    public static final IElementType GROUP = element("GROUP");
    public static final IElementType ERROR = element("ERROR_EXPRESSION");

    private static final Map<MyBatisOgnlTokenKind, IElementType> TOKENS = tokenTypes();

    private MyBatisOgnlTypes() {
    }

    public static @NotNull IElementType token(@NotNull MyBatisOgnlTokenKind kind) {
        IElementType type = TOKENS.get(kind);
        if (type == null) {
            throw new IllegalArgumentException(MyBatisOgnlMessages.message(
                    "ognl.error.platform.token.missing",
                    kind));
        }
        return type;
    }

    public static @NotNull IElementType expression(@NotNull MyBatisOgnlExpression expression) {
        return switch (expression) {
            case MyBatisOgnlExpression.Literal ignored -> LITERAL;
            case MyBatisOgnlExpression.Name ignored -> NAME;
            case MyBatisOgnlExpression.Unary ignored -> UNARY;
            case MyBatisOgnlExpression.Binary ignored -> BINARY;
            case MyBatisOgnlExpression.Conditional ignored -> CONDITIONAL;
            case MyBatisOgnlExpression.Property ignored -> PROPERTY;
            case MyBatisOgnlExpression.MethodCall ignored -> METHOD_CALL;
            case MyBatisOgnlExpression.Index ignored -> INDEX;
            case MyBatisOgnlExpression.StaticMember ignored -> STATIC_MEMBER;
            case MyBatisOgnlExpression.TypeLiteral ignored -> TYPE_LITERAL;
            case MyBatisOgnlExpression.ListLiteral ignored -> LIST_LITERAL;
            case MyBatisOgnlExpression.MapLiteral ignored -> MAP_LITERAL;
            case MyBatisOgnlExpression.Group ignored -> GROUP;
            case MyBatisOgnlExpression.Error ignored -> ERROR;
        };
    }

    private static Map<MyBatisOgnlTokenKind, IElementType> tokenTypes() {
        Map<MyBatisOgnlTokenKind, IElementType> result =
                new EnumMap<>(MyBatisOgnlTokenKind.class);
        for (MyBatisOgnlTokenKind kind : MyBatisOgnlTokenKind.values()) {
            if (kind == MyBatisOgnlTokenKind.EOF) {
                continue;
            }
            result.put(kind, kind == MyBatisOgnlTokenKind.BAD_CHARACTER
                    ? TokenType.BAD_CHARACTER
                    : new MyBatisOgnlTokenType(kind.name()));
        }
        return Map.copyOf(result);
    }

    private static IElementType element(String name) {
        return new MyBatisOgnlElementType(name);
    }
}
