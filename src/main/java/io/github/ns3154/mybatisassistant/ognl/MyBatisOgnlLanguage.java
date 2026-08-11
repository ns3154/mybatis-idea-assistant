package io.github.ns3154.mybatisassistant.ognl;

import com.intellij.lang.Language;
import org.jetbrains.annotations.NotNull;

/**
 * 注入 MyBatis XML 动态表达式属性的 OGNL 子语言。
 */
public final class MyBatisOgnlLanguage extends Language {
    public static final MyBatisOgnlLanguage INSTANCE = new MyBatisOgnlLanguage();

    private MyBatisOgnlLanguage() {
        super("MyBatisOGNL");
    }

    @Override
    public @NotNull String getDisplayName() {
        return "MyBatis OGNL";
    }
}
