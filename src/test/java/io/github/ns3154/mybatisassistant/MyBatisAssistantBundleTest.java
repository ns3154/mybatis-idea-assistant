package io.github.ns3154.mybatisassistant;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;

import java.util.Locale;
import java.util.ResourceBundle;

public final class MyBatisAssistantBundleTest extends BasePlatformTestCase {
    private MyBatisAssistantSettings.SettingsState original;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        original = MyBatisAssistantSettings.getInstance().getState();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MyBatisAssistantSettings.getInstance().replace(original);
        } finally {
            super.tearDown();
        }
    }

    public void testChineseAndEnglishBundlesHaveExactlyTheSameKeys() {
        ResourceBundle chinese = ResourceBundle.getBundle(
                "messages.MyBatisAssistantBundle", Locale.ROOT);
        ResourceBundle english = ResourceBundle.getBundle(
                "messages.MyBatisAssistantBundle", Locale.ENGLISH);

        assertEquals(chinese.keySet(), english.keySet());
        chinese.keySet().forEach(key -> {
            assertFalse(key, chinese.getString(key).isBlank());
            assertFalse(key, english.getString(key).isBlank());
        });
    }

    public void testExplicitMessageLocaleChangesImmediatelyWithoutRestart() {
        MyBatisAssistantSettings.SettingsState state = original.copyAndNormalize();
        state.uiLocale = "en";
        MyBatisAssistantSettings.getInstance().replace(state);
        assertEquals("Navigate to MyBatis XML statement",
                MyBatisAssistantBundle.message("navigation.to.statement"));
        assertEquals("Quickly Execute SQL on demo",
                MyBatisAssistantBundle.message("database.sql.execution.title", "demo"));
        assertEquals("The conversion is a preview only and will not write to the project.",
                MyBatisAssistantBundle.message("sqltool.select.conversion.preview"));

        state.uiLocale = "zh-CN";
        MyBatisAssistantSettings.getInstance().replace(state);
        assertEquals("跳转到 MyBatis XML statement",
                MyBatisAssistantBundle.message("navigation.to.statement"));
        assertEquals("在 demo 上快速执行 SQL",
                MyBatisAssistantBundle.message("database.sql.execution.title", "demo"));
        assertEquals("转换结果仅供预览，不会写入项目。",
                MyBatisAssistantBundle.message("sqltool.select.conversion.preview"));
    }
}
