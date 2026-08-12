package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/**
 * 为注入 XML 属性的 OGNL token 提供与当前 IDE 主题一致的基础着色。
 */
public final class MyBatisOgnlSyntaxHighlighter extends SyntaxHighlighterBase {
    private static final TokenSet KEYWORDS = tokens(
            MyBatisOgnlTokenKind.NULL,
            MyBatisOgnlTokenKind.TRUE,
            MyBatisOgnlTokenKind.FALSE,
            MyBatisOgnlTokenKind.AND,
            MyBatisOgnlTokenKind.OR,
            MyBatisOgnlTokenKind.NOT,
            MyBatisOgnlTokenKind.EQ,
            MyBatisOgnlTokenKind.NEQ,
            MyBatisOgnlTokenKind.LT,
            MyBatisOgnlTokenKind.LTE,
            MyBatisOgnlTokenKind.GT,
            MyBatisOgnlTokenKind.GTE,
            MyBatisOgnlTokenKind.IN,
            MyBatisOgnlTokenKind.INSTANCEOF,
            MyBatisOgnlTokenKind.NEW);
    private static final TokenSet OPERATORS = tokens(
            MyBatisOgnlTokenKind.PLUS,
            MyBatisOgnlTokenKind.MINUS,
            MyBatisOgnlTokenKind.STAR,
            MyBatisOgnlTokenKind.SLASH,
            MyBatisOgnlTokenKind.PERCENT,
            MyBatisOgnlTokenKind.BANG,
            MyBatisOgnlTokenKind.LESS,
            MyBatisOgnlTokenKind.GREATER,
            MyBatisOgnlTokenKind.LESS_EQ,
            MyBatisOgnlTokenKind.GREATER_EQ,
            MyBatisOgnlTokenKind.EQ_EQ,
            MyBatisOgnlTokenKind.NOT_EQ,
            MyBatisOgnlTokenKind.AND_AND,
            MyBatisOgnlTokenKind.OR_OR,
            MyBatisOgnlTokenKind.QUESTION,
            MyBatisOgnlTokenKind.COLON,
            MyBatisOgnlTokenKind.AT,
            MyBatisOgnlTokenKind.HASH);

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new MyBatisOgnlLexerAdapter();
    }

    @Override
    public @NotNull TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        if (KEYWORDS.contains(tokenType)) {
            return pack(DefaultLanguageHighlighterColors.KEYWORD);
        }
        if (OPERATORS.contains(tokenType)) {
            return pack(DefaultLanguageHighlighterColors.OPERATION_SIGN);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.NUMBER)) {
            return pack(DefaultLanguageHighlighterColors.NUMBER);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.STRING)) {
            return pack(DefaultLanguageHighlighterColors.STRING);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.IDENTIFIER)) {
            return pack(DefaultLanguageHighlighterColors.IDENTIFIER);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.DOT)) {
            return pack(DefaultLanguageHighlighterColors.DOT);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.COMMA)) {
            return pack(DefaultLanguageHighlighterColors.COMMA);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.LEFT_PAREN)
                || tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.RIGHT_PAREN)) {
            return pack(DefaultLanguageHighlighterColors.PARENTHESES);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.LEFT_BRACKET)
                || tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.RIGHT_BRACKET)) {
            return pack(DefaultLanguageHighlighterColors.BRACKETS);
        }
        if (tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.LEFT_BRACE)
                || tokenType == MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.RIGHT_BRACE)) {
            return pack(DefaultLanguageHighlighterColors.BRACES);
        }
        return tokenType == TokenType.BAD_CHARACTER
                ? pack(HighlighterColors.BAD_CHARACTER)
                : TextAttributesKey.EMPTY_ARRAY;
    }

    private static @NotNull TokenSet tokens(MyBatisOgnlTokenKind... kinds) {
        IElementType[] types = java.util.Arrays.stream(kinds)
                .map(MyBatisOgnlTypes::token)
                .toArray(IElementType[]::new);
        return TokenSet.create(types);
    }
}
