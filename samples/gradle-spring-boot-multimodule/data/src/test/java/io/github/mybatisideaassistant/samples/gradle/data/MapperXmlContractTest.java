package io.github.mybatisideaassistant.samples.gradle.data;

import io.github.mybatisideaassistant.samples.gradle.domain.UserCriteria;
import io.github.mybatisideaassistant.samples.gradle.domain.UserStatus;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapperXmlContractTest {
    private static final String NAMESPACE =
            "io.github.mybatisideaassistant.samples.gradle.data.mapper.UserMapper";

    @Test
    void parsesCrossModuleTypesAndDynamicSql() {
        Configuration configuration = new Configuration();

        try (InputStream input = mapperXml()) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    "mappers/UserMapper.xml",
                    configuration.getSqlFragments()
            ).parse();
        } catch (IOException exception) {
            throw new AssertionError("读取 Mapper XML 失败", exception);
        }

        assertTrue(configuration.hasStatement(NAMESPACE + ".findById"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".find"));
        assertNotNull(configuration.getResultMap(NAMESPACE + ".userMap"));
        assertNotNull(configuration.getSqlFragments().get(NAMESPACE + ".baseColumns"));

        UserCriteria criteria = new UserCriteria("示例", UserStatus.ACTIVE);
        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + ".find")
                .getBoundSql(Map.of("criteria", criteria));
        String normalizedSql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(normalizedSql.contains("name like concat('%', ?, '%')"));
        assertTrue(normalizedSql.contains("status = ?"));
    }

    private static InputStream mapperXml() {
        InputStream input = MapperXmlContractTest.class.getResourceAsStream("/mappers/UserMapper.xml");
        assertNotNull(input, "缺少 data 模块的 Mapper XML");
        return input;
    }
}
