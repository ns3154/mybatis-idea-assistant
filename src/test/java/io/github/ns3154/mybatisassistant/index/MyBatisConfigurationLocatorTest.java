package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntryKind;

import java.util.List;

public final class MyBatisConfigurationLocatorTest extends BasePlatformTestCase {
    public void testFindsExactAliasesCaseInsensitivelyAndAllPackages() {
        addConfig("config/mybatis-config.xml", """
                <configuration>
                    <typeAliases>
                        <typeAlias alias="UserAlias" type="com.example.User"/>
                        <package name="com.example.domain"/>
                    </typeAliases>
                    <mappers>
                        <mapper resource="mapper/UserMapper.xml"/>
                    </mappers>
                </configuration>
                """);

        List<XmlTag> aliases = find(MyBatisConfigurationEntryKind.TYPE_ALIAS, "USERALIAS");
        List<XmlTag> packages = findAll(MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE);
        List<XmlTag> resources = find(
                MyBatisConfigurationEntryKind.MAPPER_RESOURCE,
                "mapper/UserMapper.xml");

        assertSize(1, aliases);
        assertEquals("typeAlias", aliases.getFirst().getName());
        assertSize(1, packages);
        assertEquals("package", packages.getFirst().getName());
        assertSize(1, resources);
    }

    public void testPreservesDuplicateAliasDeclarationsAcrossFiles() {
        addConfig("config/one.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="User" type="com.example.One"/>
                </typeAliases></configuration>
                """);
        addConfig("config/two.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="User" type="com.example.Two"/>
                </typeAliases></configuration>
                """);

        assertSize(2, find(MyBatisConfigurationEntryKind.TYPE_ALIAS, "user"));
    }

    public void testMalformedConfigurationDoesNotEnterIndex() {
        addConfig("config/broken.xml", """
                <configuration><typeAliases>
                    <package name="com.example.domain">
                """);

        assertEmpty(findAll(MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE));
    }

    public void testUnsavedAliasChangeInvalidatesIndex() {
        PsiFile file = addConfig("config/mybatis-config.xml", """
                <configuration><typeAliases>
                    <typeAlias alias="Before" type="com.example.User"/>
                </typeAliases></configuration>
                """);
        XmlTag typeAliases = ((XmlFile) file).getRootTag().findFirstSubTag("typeAliases");
        assertNotNull(typeAliases);
        XmlTag typeAlias = typeAliases.findFirstSubTag("typeAlias");
        assertNotNull(typeAlias);
        assertSize(1, find(MyBatisConfigurationEntryKind.TYPE_ALIAS, "before"));

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            typeAlias.setAttribute("alias", "After");
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        assertEmpty(find(MyBatisConfigurationEntryKind.TYPE_ALIAS, "before"));
        assertSize(1, find(MyBatisConfigurationEntryKind.TYPE_ALIAS, "after"));
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return findAll(MyBatisConfigurationEntryKind.MAPPER_PACKAGE);
                    },
                    indicator);
            fail("取消后的配置查询必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，配置定位器必须向上传播。
        }
    }

    private PsiFile addConfig(String path, String xml) {
        return myFixture.addFileToProject("src/main/resources/" + path, xml);
    }

    private List<XmlTag> find(MyBatisConfigurationEntryKind kind, String name) {
        return ReadAction.compute(() -> MyBatisConfigurationLocator.find(getProject(), kind, name));
    }

    private List<XmlTag> findAll(MyBatisConfigurationEntryKind kind) {
        return ReadAction.compute(() -> MyBatisConfigurationLocator.findAll(getProject(), kind));
    }
}
