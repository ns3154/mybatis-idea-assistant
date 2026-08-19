package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * IntelliJ PSI 树中的 OGNL 复合节点类型。
 */
public final class MyBatisOgnlElementType extends IElementType {
    public MyBatisOgnlElementType(@NotNull String debugName) {
        super(debugName, MyBatisOgnlLanguage.INSTANCE);
    }
}
