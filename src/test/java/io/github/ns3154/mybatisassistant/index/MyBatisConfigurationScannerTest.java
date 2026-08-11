package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisConfigurationEntryKind;

import java.util.Set;

public final class MyBatisConfigurationScannerTest extends BasePlatformTestCase {
    public void testScansTypeAliasesAndMapperDeclarations() {
        Set<MyBatisConfigurationEntry> entries = MyBatisConfigurationScanner.scan("""
                <configuration>
                    <typeAliases>
                        <typeAlias alias="UserAlias" type="com.example.User"/>
                        <typeAlias type="com.example.Order"/>
                        <package name="com.example.domain"/>
                    </typeAliases>
                    <mappers>
                        <mapper resource="mapper/UserMapper.xml"/>
                        <mapper url="file:///opt/mapper/OrderMapper.xml"/>
                        <mapper class="com.example.UserMapper"/>
                        <package name="com.example.mapper"/>
                    </mappers>
                </configuration>
                """);

        assertEquals(7, entries.size());
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.TYPE_ALIAS,
                "useralias",
                "com.example.User")));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.TYPE_ALIAS,
                "order",
                "com.example.Order")));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.TYPE_ALIAS_PACKAGE,
                "com.example.domain",
                null)));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.MAPPER_RESOURCE,
                "mapper/UserMapper.xml",
                null)));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.MAPPER_URL,
                "file:///opt/mapper/OrderMapper.xml",
                null)));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.MAPPER_CLASS,
                "com.example.UserMapper",
                null)));
        assertTrue(entries.contains(new MyBatisConfigurationEntry(
                MyBatisConfigurationEntryKind.MAPPER_PACKAGE,
                "com.example.mapper",
                null)));
    }

    public void testRejectsMalformedUnrelatedAndNestedConfigurationEntries() {
        assertEmpty(MyBatisConfigurationScanner.scan("""
                <configuration>
                    <typeAliases><package name="com.example.domain"/>
                """));
        assertEmpty(MyBatisConfigurationScanner.scan("""
                <mapper namespace="com.example.UserMapper"/>
                """));
        assertEmpty(MyBatisConfigurationScanner.scan("""
                <configuration>
                    <settings>
                        <typeAliases><package name="com.example.domain"/></typeAliases>
                    </settings>
                </configuration>
                """));
    }

    public void testIgnoresPrefixedTagsAndAttributesAndDoesNotResolveEntities() {
        Set<MyBatisConfigurationEntry> entries = MyBatisConfigurationScanner.scan("""
                <!DOCTYPE configuration [
                    <!ENTITY secret SYSTEM "file:///definitely-not-readable/mybatis-config-secret">
                ]>
                <configuration xmlns:x="urn:test">
                    <typeAliases>
                        <typeAlias x:alias="Secret" type="&secret;"/>
                        <x:package name="com.example.ignored"/>
                    </typeAliases>
                </configuration>
                """);

        assertEmpty(entries);
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return MyBatisConfigurationScanner.scan("<configuration/>");
                    },
                    indicator);
            fail("取消后的配置扫描必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，配置扫描器必须向上传播。
        }
    }
}
