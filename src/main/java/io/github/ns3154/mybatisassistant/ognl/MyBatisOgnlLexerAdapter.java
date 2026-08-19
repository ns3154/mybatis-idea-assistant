package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 将纯词法器适配为 IntelliJ 增量 Lexer API。
 */
public final class MyBatisOgnlLexerAdapter extends LexerBase {
    private CharSequence buffer = "";
    private int bufferEnd;
    private List<Slice> slices = List.of();
    private int index;

    @Override
    public void start(
            @NotNull CharSequence source,
            int startOffset,
            int endOffset,
            int initialState) {
        buffer = source;
        bufferEnd = endOffset;
        index = 0;
        List<Slice> result = new ArrayList<>();
        CharSequence fragment = source.subSequence(startOffset, endOffset);
        MyBatisOgnlLexResult lexed = MyBatisOgnlLexer.lex(fragment);
        int cursor = startOffset;
        for (MyBatisOgnlToken token : lexed.tokens()) {
            if (token.kind() == MyBatisOgnlTokenKind.EOF) {
                continue;
            }
            int tokenStart = startOffset + token.range().startOffset();
            int tokenEnd = startOffset + token.range().endOffset();
            if (cursor < tokenStart) {
                result.add(new Slice(TokenType.WHITE_SPACE, cursor, tokenStart));
            }
            result.add(new Slice(MyBatisOgnlTypes.token(token.kind()), tokenStart, tokenEnd));
            cursor = tokenEnd;
        }
        if (cursor < endOffset) {
            result.add(new Slice(TokenType.WHITE_SPACE, cursor, endOffset));
        }
        slices = List.copyOf(result);
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return index < slices.size() ? slices.get(index).type() : null;
    }

    @Override
    public int getTokenStart() {
        return index < slices.size() ? slices.get(index).startOffset() : bufferEnd;
    }

    @Override
    public int getTokenEnd() {
        return index < slices.size() ? slices.get(index).endOffset() : bufferEnd;
    }

    @Override
    public void advance() {
        if (index < slices.size()) {
            index++;
        }
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    private record Slice(
            @NotNull IElementType type,
            int startOffset,
            int endOffset) {
    }
}
