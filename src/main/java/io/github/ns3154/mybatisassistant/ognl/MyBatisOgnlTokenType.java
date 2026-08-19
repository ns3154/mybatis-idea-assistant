package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * IntelliJ lexer 使用的 OGNL token 类型。
 */
public final class MyBatisOgnlTokenType extends IElementType {
    public MyBatisOgnlTokenType(@NotNull String debugName) {
        super(debugName, MyBatisOgnlLanguage.INSTANCE);
    }
}
