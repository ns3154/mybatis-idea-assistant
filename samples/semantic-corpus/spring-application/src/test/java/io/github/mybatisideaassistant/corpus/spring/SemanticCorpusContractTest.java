package io.github.mybatisideaassistant.corpus.spring;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SemanticCorpusContractTest {
    private static final String NAMESPACE = "io.github.mybatisideaassistant.corpus.java.mapper.UserMapper";

    @Test
    public void parsesCrossModuleMapperAndDynamicSql() {
        Configuration configuration = new Configuration();
        try (InputStream input = resource("/mappers/UserMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mappers/UserMapper.xml", configuration.getSqlFragments()).parse();
        } catch (Exception exception) {
            throw new AssertionError("正常 Mapper XML 应可由 MyBatis 解析", exception);
        }

        assertTrue(configuration.hasStatement(NAMESPACE + ".findById"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".findPage"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".updateUser"));
        assertTrue(configuration.getResultMap(NAMESPACE + ".userMap").hasNestedResultMaps());
        assertNotNull(configuration.getSqlFragments().get(NAMESPACE + ".userColumns"));
    }

    @Test
    public void keepsNamespaceMismatchAsIsolatedFailureCorpus() throws Exception {
        try (InputStream input = resource("/corpus/failures/namespace-mismatch/UserMapper.xml")) {
            var documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = documentBuilderFactory.newDocumentBuilder().parse(input);
            assertEquals("io.github.mybatisideaassistant.corpus.wrong.UserMapper",
                    document.getDocumentElement().getAttribute("namespace"));
        }
    }

    private static InputStream resource(String path) {
        InputStream input = SemanticCorpusContractTest.class.getResourceAsStream(path);
        assertNotNull("缺少测试资源：" + path, input);
        return input;
    }
}
