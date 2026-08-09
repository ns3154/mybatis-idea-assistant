package io.github.mybatisideaassistant.sample;

import io.github.mybatisideaassistant.sample.domain.User;
import io.github.mybatisideaassistant.sample.mapper.UserMapper;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实 MyBatis 代理验证 Mapper 接口、namespace 和 statement id 的成功关联。
 */
class MyBatisAssociationTest {

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void createDatabase() throws IOException, SQLException {
        try (Reader configuration = Resources.getResourceAsReader("mybatis-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        }

        try (SqlSession session = sqlSessionFactory.openSession();
             Connection connection = session.getConnection();
             Reader schema = Resources.getResourceAsReader("schema.sql")) {
            ScriptRunner runner = new ScriptRunner(connection);
            runner.setLogWriter(null);
            runner.runScript(schema);
            connection.commit();
        }
    }

    @Test
    void 应通过方法名找到并执行对应Select() {
        assertTrue(sqlSessionFactory.getConfiguration().hasMapper(UserMapper.class));
        assertTrue(sqlSessionFactory.getConfiguration().hasStatement(
                "io.github.mybatisideaassistant.sample.mapper.UserMapper.findById"));

        try (SqlSession session = sqlSessionFactory.openSession()) {
            User user = session.getMapper(UserMapper.class).findById(1L);

            assertNotNull(user);
            assertEquals(1L, user.getId());
            assertEquals("测试用户", user.getUsername());
            assertEquals("test@example.com", user.getEmail());
        }
    }

    @Test
    void 应通过对应Insert写入并回填主键() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = new User(null, "新增用户", "new@example.com");

            assertEquals(1, mapper.insert(user));
            assertNotNull(user.getId());
            session.commit();

            User saved = mapper.findById(user.getId());
            assertEquals("新增用户", saved.getUsername());
        }
    }
}
