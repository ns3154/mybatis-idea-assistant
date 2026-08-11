package io.github.ns3154.mybatisassistant.sqltool.intellij;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.database.MyBatisSqlDialect;

import java.util.Arrays;
import java.util.List;

public final class MyBatisConversionActionsTest extends BasePlatformTestCase {
    public void testDescriptorRegistersConversionActions() {
        ActionManager manager = ActionManager.getInstance();
        AnAction ddl = manager.getAction(MyBatisDdlToArtifactsAction.ID);
        AnAction java = manager.getAction(MyBatisJavaToDdlAction.ID);
        AnAction junit = manager.getAction(MyBatisMapperJUnitSkeletonAction.ID);
        AnAction select = manager.getAction(MyBatisSelectToArtifactsAction.ID);

        assertInstanceOf(ddl, MyBatisDdlToArtifactsAction.class);
        assertInstanceOf(java, MyBatisJavaToDdlAction.class);
        assertInstanceOf(junit, MyBatisMapperJUnitSkeletonAction.class);
        assertInstanceOf(select, MyBatisSelectToArtifactsAction.class);
        assertEquals("CREATE TABLE 转 MyBatis 产物…", ddl.getTemplateText());
        assertEquals("Java 类生成 DDL…", java.getTemplateText());
        assertEquals("生成 Mapper JUnit 测试骨架…", junit.getTemplateText());
        assertEquals("SELECT 转 MyBatis 产物…", select.getTemplateText());
        List<AnAction> tools = children(manager, "ToolsMenu");
        assertTrue(tools.contains(ddl));
        assertTrue(tools.contains(java));
        assertTrue(tools.contains(junit));
        assertTrue(tools.contains(select));
        assertTrue(children(manager, "EditorPopupMenu").contains(java));
        assertTrue(children(manager, "EditorPopupMenu").contains(junit));
    }

    public void testSelectActionRendersMapperXmlResultMapAndRowClass() {
        String rendered = MyBatisSelectToArtifactsAction.render(
                "SELECT id, display_name FROM users WHERE id = ?",
                "com.example",
                "UserQueryMapper",
                "findUser");

        assertTrue(rendered.contains("===== Mapper.java ====="));
        assertTrue(rendered.contains("===== Mapper.xml ====="));
        assertTrue(rendered.contains("<resultMap id=\"findUserResultMap\""));
        assertTrue(rendered.contains("===== Row.java ====="));
        assertTrue(rendered.contains("#{param1,jdbcType=OTHER}"));
    }

    public void testDdlActionRendersArtifactsAndTypedFailure() {
        String success = MyBatisDdlToArtifactsAction.render(
                "CREATE TABLE account (id BIGINT PRIMARY KEY, name VARCHAR(100))",
                MyBatisSqlDialect.MYSQL,
                "com.example");
        String failure = MyBatisDdlToArtifactsAction.render(
                "DROP TABLE account",
                MyBatisSqlDialect.MYSQL,
                "com.example");

        assertTrue(success.contains("Account.java"));
        assertTrue(success.contains("AccountMapper.java"));
        assertTrue(success.contains("AccountMapper.xml"));
        assertTrue(success.contains("<resultMap id=\"BaseResultMap\""));
        assertTrue(failure.contains("NOT_CREATE_TABLE"));
        assertTrue(failure.contains("仅支持单条 CREATE TABLE"));
    }

    public void testJavaActionBuildsPreviewAndUpdateRequiresJavaClass() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("UserAccount.java", """
                package com.example;
                /** 用户账户 */
                public class UserAccount {
                    private long id;
                    private String displayName;
                }
                """);
        PsiClass source = file.getClasses()[0];

        MyBatisJavaToDdlAction.Preview preview = MyBatisJavaToDdlAction.buildPreview(
                source, MyBatisSqlDialect.POSTGRESQL);

        assertNull(preview.failure());
        assertTrue(preview.text().contains("CREATE TABLE \"user_account\""));
        assertTrue(preview.text().contains("\"display_name\" VARCHAR(255)"));
        MyBatisJavaToDdlAction action = new MyBatisJavaToDdlAction();
        assertTrue(update(action, file).isEnabledAndVisible());
        PsiFile xml = myFixture.configureByText("sample.xml", "<root/>\n");
        assertFalse(update(action, xml).isEnabledAndVisible());
    }

    public void testJavaActionReportsUnsupportedSource() {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText(
                "Sample.java", "public interface Sample {}\n");

        MyBatisJavaToDdlAction.Preview preview = MyBatisJavaToDdlAction.buildPreview(
                file.getClasses()[0], MyBatisSqlDialect.GENERIC);

        assertNotNull(preview.failure());
        assertTrue(preview.text().isEmpty());
    }

    private Presentation update(AnAction action, PsiFile file) {
        DataContext context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.PSI_FILE, file)
                .build();
        Presentation presentation = new Presentation();
        action.update(AnActionEvent.createEvent(
                action, context, presentation, "S10 转换测试", ActionUiKind.NONE, null));
        return presentation;
    }

    private static List<AnAction> children(ActionManager manager, String groupId) {
        ActionGroup group = assertInstanceOf(manager.getAction(groupId), ActionGroup.class);
        return Arrays.asList(group.getChildren(null));
    }
}
