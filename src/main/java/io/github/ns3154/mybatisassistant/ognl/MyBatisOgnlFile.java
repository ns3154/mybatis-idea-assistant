package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/**
 * XML 属性注入产生的 OGNL PSI 文件。
 */
public final class MyBatisOgnlFile extends PsiFileBase {
    public MyBatisOgnlFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, MyBatisOgnlLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return MyBatisOgnlFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "MyBatis OGNL File";
    }
}
