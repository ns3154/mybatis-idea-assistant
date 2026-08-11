package io.github.ns3154.mybatisassistant.index;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntry;
import io.github.ns3154.mybatisassistant.model.MyBatisBootConfigurationEntryKind;

import java.util.Set;

public final class MyBatisBootConfigurationScannerTest extends BasePlatformTestCase {
    public void testScansPropertiesAndSplitsMultiValueSettings() {
        Set<MyBatisBootConfigurationEntry> entries = MyBatisBootConfigurationScanner.scan("""
                spring.application.name=sample
                mybatis.config-location=classpath:mybatis-config.xml
                mybatis.mapper-locations=classpath*:mapper/**/*.xml, classpath*:extra/*.xml
                mybatis.type-aliases-package=com.example.domain;com.example.shared
                mybatis.type-handlers-package=com.example.handler
                mybatis-plus.mapper-locations=classpath*:plus/*.xml
                """, "properties");

        assertEquals(7, entries.size());
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.CONFIG_LOCATION,
                "classpath:mybatis-config.xml")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:mapper/**/*.xml")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:extra/*.xml")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:plus/*.xml")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.domain")));
    }

    public void testScansYamlScalarsListsInlineListsAndProfiles() {
        Set<MyBatisBootConfigurationEntry> entries = MyBatisBootConfigurationScanner.scan("""
                mybatis:
                  config-location: 'classpath:mybatis-config.xml'
                  mapper-locations:
                    - classpath*:mapper/**/*.xml
                    - "classpath*:extra/*.xml#fragment"
                  type-aliases-package: [com.example.domain, com.example.shared]
                ---
                mybatis-plus:
                  mapper-locations: classpath*:plus/*.xml # 当前环境
                """, "yaml");

        assertEquals(6, entries.size());
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.CONFIG_LOCATION,
                "classpath:mybatis-config.xml")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:extra/*.xml#fragment")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.shared")));
        assertTrue(entries.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:plus/*.xml")));
    }

    public void testSupportsSpringBootRelaxedBindingNames() {
        Set<MyBatisBootConfigurationEntry> properties = MyBatisBootConfigurationScanner.scan("""
                mybatis.mapperLocations=classpath*:camel/*.xml
                mybatis.type_aliases_package=com.example.underscore
                """, "properties");
        Set<MyBatisBootConfigurationEntry> yaml = MyBatisBootConfigurationScanner.scan("""
                mybatis:
                  configLocation: classpath:camel-config.xml
                  type_handlers_package: com.example.handler
                """, "yaml");

        assertTrue(properties.contains(entry(
                MyBatisBootConfigurationEntryKind.MAPPER_LOCATION,
                "classpath*:camel/*.xml")));
        assertTrue(properties.contains(entry(
                MyBatisBootConfigurationEntryKind.TYPE_ALIASES_PACKAGE,
                "com.example.underscore")));
        assertTrue(yaml.contains(entry(
                MyBatisBootConfigurationEntryKind.CONFIG_LOCATION,
                "classpath:camel-config.xml")));
        assertTrue(yaml.contains(entry(
                MyBatisBootConfigurationEntryKind.TYPE_HANDLERS_PACKAGE,
                "com.example.handler")));
    }

    public void testIgnoresPlaceholdersUnknownKeysTabsAndUnsupportedFiles() {
        assertEmpty(MyBatisBootConfigurationScanner.scan("""
                mybatis:
                \tmapper-locations: classpath*:ignored/*.xml
                  mapper-locations: ${MYBATIS_MAPPERS}
                  configuration:
                    map-underscore-to-camel-case: true
                """, "yml"));
        assertEmpty(MyBatisBootConfigurationScanner.scan(
                "mybatis.mapper-locations=classpath*:mapper/*.xml",
                "toml"));
    }

    public void testCancellationPropagates() {
        EmptyProgressIndicator indicator = new EmptyProgressIndicator();

        try {
            ProgressManager.getInstance().runProcess(
                    () -> {
                        indicator.cancel();
                        return MyBatisBootConfigurationScanner.scan("mybatis: {}", "yml");
                    },
                    indicator);
            fail("取消后的 Boot 配置扫描必须抛出 ProcessCanceledException");
        } catch (ProcessCanceledException expected) {
            // 取消是正常控制流，Boot 配置扫描器必须向上传播。
        }
    }

    private MyBatisBootConfigurationEntry entry(
            MyBatisBootConfigurationEntryKind kind,
            String value) {
        return new MyBatisBootConfigurationEntry(kind, value);
    }
}
