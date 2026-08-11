package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * MyBatis OGNL 注入语言的 261 公共 ParserDefinition。
 */
public final class MyBatisOgnlParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE =
            new IFileElementType(MyBatisOgnlLanguage.INSTANCE);

    @Override
    public @NotNull Lexer createLexer(@Nullable Project project) {
        return new MyBatisOgnlLexerAdapter();
    }

    @Override
    public @NotNull PsiParser createParser(@Nullable Project project) {
        return new MyBatisOgnlPsiParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(TokenType.WHITE_SPACE);
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return TokenSet.EMPTY;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return TokenSet.create(MyBatisOgnlTypes.token(MyBatisOgnlTokenKind.STRING));
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        return new MyBatisOgnlPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new MyBatisOgnlFile(viewProvider);
    }
}
