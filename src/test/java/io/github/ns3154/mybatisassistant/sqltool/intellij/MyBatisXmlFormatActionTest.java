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
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;

public final class MyBatisXmlFormatActionTest extends BasePlatformTestCase {
    public void testDescriptorRegistersActionInToolsAndEditorMenus() {
        ActionManager manager = ActionManager.getInstance();
        AnAction action = manager.getAction(MyBatisXmlFormatAction.ID);

        assertInstanceOf(action, MyBatisXmlFormatAction.class);
        assertEquals("格式化 MyBatis XML…", action.getTemplateText());
        assertTrue(children(manager, "ToolsMenu").contains(action));
        assertTrue(children(manager, "EditorPopupMenu").contains(action));
    }

    public void testUpdateOnlyEnablesForMapperXml() {
        MyBatisXmlFormatAction action = new MyBatisXmlFormatAction();
        PsiFile mapper = myFixture.configureByText(
                "UserMapper.xml", "<mapper namespace=\"x.UserMapper\"/>\n");
        assertTrue(update(action, mapper).isEnabledAndVisible());

        PsiFile ordinary = myFixture.configureByText("ordinary.xml", "<root/>\n");
        assertFalse(update(action, ordinary).isEnabledAndVisible());
        PsiFile java = myFixture.configureByText("Sample.java", "class Sample {}\n");
        assertFalse(update(action, java).isEnabledAndVisible());
    }

    public void testApplyUsesOneUndoableCommand() {
        String original = """
                <mapper namespace="x.UserMapper">
                <select id="find">
                SELECT 1
                </select>
                </mapper>
                """;
        String formatted = """
                <mapper namespace="x.UserMapper">
                  <select id="find">
                    SELECT 1
                  </select>
                </mapper>
                """;
        XmlFile file = (XmlFile) myFixture.configureByText("UserMapper.xml", original);
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);

        MyBatisXmlFormatAction.ApplyResult result = MyBatisXmlFormatAction.apply(
                getProject(), file, original, formatted);

        assertEquals(MyBatisXmlFormatAction.ApplyResult.APPLIED, result);
        assertEquals(formatted, document.getText());
        FileEditor editor = FileEditorManager.getInstance(getProject())
                .getSelectedEditor(file.getVirtualFile());
        assertNotNull(editor);
        UndoManager undoManager = UndoManager.getInstance(getProject());
        assertTrue(undoManager.isUndoAvailable(editor));
        TestDialogManager.setTestDialog(TestDialog.OK, getTestRootDisposable());
        EdtTestUtil.runInEdtAndWait(() -> undoManager.undo(editor));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(original, document.getText());
    }

    public void testApplyRejectsPreviewRaceAndUnchangedText() {
        String original = "<mapper namespace=\"x.Mapper\"/>\n";
        XmlFile file = (XmlFile) myFixture.configureByText("Mapper.xml", original);
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);

        assertEquals(MyBatisXmlFormatAction.ApplyResult.UNCHANGED,
                MyBatisXmlFormatAction.apply(getProject(), file, original, original));
        WriteAction.runAndWait(() -> document.setText(
                "<mapper namespace=\"x.Changed\"/>\n"));
        assertEquals(MyBatisXmlFormatAction.ApplyResult.SOURCE_CHANGED,
                MyBatisXmlFormatAction.apply(
                        getProject(), file, original, "<mapper namespace=\"x.Mapper\">\n</mapper>\n"));
        assertTrue(document.getText().contains("x.Changed"));
    }

    private Presentation update(AnAction action, PsiFile file) {
        DataContext context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.PSI_FILE, file)
                .build();
        Presentation presentation = new Presentation();
        AnActionEvent event = AnActionEvent.createEvent(
                action, context, presentation, "S10 格式化测试", ActionUiKind.NONE, null);
        action.update(event);
        return presentation;
    }

    private static java.util.List<AnAction> children(ActionManager manager, String groupId) {
        ActionGroup group = assertInstanceOf(manager.getAction(groupId), ActionGroup.class);
        return Arrays.asList(group.getChildren(null));
    }
}
