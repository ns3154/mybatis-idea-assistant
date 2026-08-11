package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntryKind;

import java.util.List;

public final class MyBatisBootConfigurationLocatorTest extends BasePlatformTestCase {
    public void testFindsEntriesAcrossPropertiesAndYaml() {
        myFixture.addFileToProject("src/main/resources/application.properties", """
                mybatis.mapper-locations=classpath*:mapper/**/*.xml
                mybatis.type-aliases-package=com.example.domain
                """);
        myFixture.addFileToProject("src/main/resources/application.yml", """
                mybatis:
                  mapper-locations: classpath*:extra/*.xml
                  type-aliases-package: com.example.shared
                """);

        assertSize(1, find(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:mapper/**/*.xml"));
        assertEquals(
                List.of(
                        "com.example.domain",
                        "com.example.shared"),
                findAll(MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE)
                        .stream()
                        .map(MyBatisBootConfigurationEntry::value)
                        .toList());
    }

    public void testUnsavedPropertyChangeInvalidatesIndex() {
        PsiFile file = myFixture.addFileToProject("src/main/resources/application.properties", """
                mybatis.type-aliases-package=com.example.before
                """);
        assertSize(1, find(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.before"));
        Document document = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        assertNotNull(document);

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText("mybatis.type-aliases-package=com.example.after\n");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertEmpty(find(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.before"));
        assertSize(1, find(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.after"));
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return findAll(MyBatisBootConfigurationEntryKind.MAPPER_LOCATION);
                    },
                    indicator);
            fail("取消后的 Boot 配置查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，Boot 配置定位器必须向上传播。
        }
    }

    private List<MyBatisBootConfigurationEntry> find(
            MyBatisBootConfigurationEntryKind kind,
            String value) {
        return ReadAction.compute(() -> MyBatisBootConfigurationLocator.find(getProject(), kind, value));
    }

    private List<MyBatisBootConfigurationEntry> findAll(MyBatisBootConfigurationEntryKind kind) {
        return ReadAction.compute(() -> MyBatisBootConfigurationLocator.findAll(getProject(), kind));
    }
}
