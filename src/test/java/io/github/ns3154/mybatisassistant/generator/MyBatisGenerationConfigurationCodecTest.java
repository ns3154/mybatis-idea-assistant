package io.github.ns3154.mybatisassistant.generator;

import junit.framework.TestCase;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MyBatisGenerationConfigurationCodecTest extends TestCase {
    public void testRoundTripIsDeterministicAndKeepsUnicodeOverrides() {
        MyBatisGenerationConfiguration configuration = new MyBatisGenerationConfiguration(
                "com.example",
                "module/src/main/java",
                "module/src/main/resources",
                EnumSet.of(MyBatisGenerationArtifactKind.ENTITY,
                        MyBatisGenerationArtifactKind.XML),
                MyBatisGenerationTemplateGroup.MYBATIS_PLUS,
                "t_",
                "Entity",
                false,
                true,
                Set.of("删除,标识", "deleted_at"),
                Map.of("显示名称", new MyBatisGenerationColumnOverride(
                        Optional.of("displayName"),
                        Optional.of("java.lang.String"),
                        Optional.of("com.example.NameHandler"))));

        String first = MyBatisGenerationConfigurationCodec.encode(configuration);
        MyBatisGenerationConfiguration decoded = MyBatisGenerationConfigurationCodec.decode(first);
        String second = MyBatisGenerationConfigurationCodec.encode(decoded);

        assertEquals(configuration, decoded);
        assertEquals(first, second);
        assertFalse(first.contains("\r"));
        assertTrue(first.startsWith("artifacts="));
    }

    public void testRejectsUnknownMissingDuplicateAndInvalidValues() {
        expectFailure("format=1\nunknown=value\n");
        expectFailure("format=2\n");
        expectFailure("format=1\nformat=1\n");
        expectFailure("format=1\ninvalid-line\n");
        String valid = MyBatisGenerationConfigurationCodec.encode(
                MyBatisGenerationConfiguration.standard("com.example"));
        expectFailure(valid.replace("generateComments=true", "generateComments=yes"));
        expectFailure(valid.replace("templateGroup=STANDARD", "templateGroup=BROKEN"));
    }

    private static void expectFailure(String text) {
        try {
            MyBatisGenerationConfigurationCodec.decode(text);
            fail("损坏或未知配置必须停止导入");
        } catch (IllegalArgumentException expected) {
            // 导入失败不会产生部分配置。
        }
    }
}
