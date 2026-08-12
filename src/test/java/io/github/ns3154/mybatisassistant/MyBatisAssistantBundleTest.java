package io.github.ns3154.mybatisassistant;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisDatabaseColumn;
import io.github.ns3154.mybatisassistant.dynamic.MyBatisTextRange;
import io.github.ns3154.mybatisassistant.ognl.MyBatisOgnlLexer;
import io.github.ns3154.mybatisassistant.resolve.MyBatisStatementResolution;
import io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPolicy;
import io.github.ns3154.mybatisassistant.sqltool.execution.MyBatisSqlExecutionPreparation;
import io.github.ns3154.mybatisassistant.sqltool.format.MyBatisXmlFormatResult;
import io.github.ns3154.mybatisassistant.sqltool.format.MyBatisXmlFormatter;

import java.util.List;
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
        assertEquals("EXECUTE DANGEROUS SQL",
                MyBatisAssistantBundle.message("sqltool.execution.confirmation.phrase"));
        assertEquals("Read-only",
                MyBatisAssistantBundle.message("sqltool.risk.read.only"));
        assertEquals("SQL must not be empty",
                ((MyBatisSqlExecutionPreparation.Rejected)
                        MyBatisSqlExecutionPolicy.prepare("", "")).message());
        assertEquals("Indent width must be between 1 and 8",
                ((MyBatisXmlFormatResult.Failure)
                        MyBatisXmlFormatter.format("<mapper/>", 0)).message());
        assertEquals("Unrecognized OGNL character: ;",
                MyBatisOgnlLexer.lex(";").diagnostics().getFirst().message());
        assertEquals("The text range must satisfy 0 <= start <= end",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisTextRange(-1, 0)).getMessage());
        assertEquals("Column name cannot be empty",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisDatabaseColumn(
                                "", "VARCHAR", 12, true, false, false, 0)).getMessage());
        assertEquals("A multiple-match result requires at least two targets",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisStatementResolution.MultipleMatches(
                                List.of())).getMessage());

        state.uiLocale = "zh-CN";
        MyBatisAssistantSettings.getInstance().replace(state);
        assertEquals("跳转到 MyBatis XML statement",
                MyBatisAssistantBundle.message("navigation.to.statement"));
        assertEquals("在 demo 上快速执行 SQL",
                MyBatisAssistantBundle.message("database.sql.execution.title", "demo"));
        assertEquals("转换结果仅供预览，不会写入项目。",
                MyBatisAssistantBundle.message("sqltool.select.conversion.preview"));
        assertEquals("执行危险SQL",
                MyBatisAssistantBundle.message("sqltool.execution.confirmation.phrase"));
        assertEquals("只读",
                MyBatisAssistantBundle.message("sqltool.risk.read.only"));
        assertEquals("SQL 不能为空",
                ((MyBatisSqlExecutionPreparation.Rejected)
                        MyBatisSqlExecutionPolicy.prepare("", "")).message());
        assertEquals("缩进宽度必须在 1 到 8 之间",
                ((MyBatisXmlFormatResult.Failure)
                        MyBatisXmlFormatter.format("<mapper/>", 0)).message());
        assertEquals("无法识别的 OGNL 字符：;",
                MyBatisOgnlLexer.lex(";").diagnostics().getFirst().message());
        assertEquals("文本范围必须满足 0 <= start <= end",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisTextRange(-1, 0)).getMessage());
        assertEquals("列名不能为空",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisDatabaseColumn(
                                "", "VARCHAR", 12, true, false, false, 0)).getMessage());
        assertEquals("多候选结果至少需要两个目标",
                org.junit.Assert.assertThrows(IllegalArgumentException.class,
                        () -> new MyBatisStatementResolution.MultipleMatches(
                                List.of())).getMessage());
    }
}
