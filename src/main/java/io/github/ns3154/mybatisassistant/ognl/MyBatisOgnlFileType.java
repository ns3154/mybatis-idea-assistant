package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * 仅供注入 PSI 使用的内存文件类型，不注册独立磁盘扩展名。
 */
public final class MyBatisOgnlFileType extends LanguageFileType {
    public static final MyBatisOgnlFileType INSTANCE = new MyBatisOgnlFileType();

    private MyBatisOgnlFileType() {
        super(MyBatisOgnlLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "MyBatis OGNL";
    }

    @Override
    public @NotNull String getDescription() {
        return MyBatisOgnlMessages.message("ognl.file.type.description");
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "ognl";
    }

    @Override
    public @Nullable Icon getIcon() {
        return null;
    }
}
