package io.github.mybatisideaassistant.sample;

import io.github.mybatisideaassistant.sample.fixtures.namespace.NamespaceMismatchMapper;
import io.github.mybatisideaassistant.sample.fixtures.statement.StatementMissingMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定两类故意失败的语料，防止维护样例时意外把它们修成正常关联。
 */
class FailureCorpusContractTest {

    @Test
    void namespace不匹配语料应保持错误全限定名() throws IOException {
        String xml = readResource(
                "corpus/failures/namespace-mismatch/NamespaceMismatchMapper.xml");
        String mapperName = NamespaceMismatchMapper.class.getName();

        assertTrue(xml.contains("WrongNamespaceMapper"));
        assertFalse(xml.contains("namespace=\"" + mapperName + "\""));
    }

    @Test
    void statement缺失语料应保持正确Namespace和缺失方法() throws IOException {
        String xml = readResource(
                "corpus/failures/statement-missing/StatementMissingMapper.xml");
        String mapperName = StatementMissingMapper.class.getName();

        assertTrue(xml.contains("namespace=\"" + mapperName + "\""));
        assertTrue(xml.contains("id=\"findAll\""));
        assertFalse(xml.contains("id=\"findById\""));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = FailureCorpusContractTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(input, "测试资源不存在：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
