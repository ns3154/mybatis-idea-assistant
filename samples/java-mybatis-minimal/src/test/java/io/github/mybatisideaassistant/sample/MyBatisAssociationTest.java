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
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void 应执行S9生成的比较排序模糊与集合条件() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);

            List<User> containing = mapper
                    .findByUsernameContainingAndIdGreaterThanOrderByIdDesc("测试", 0L);
            List<User> in = mapper.findByIdIn(List.of(1L));

            assertEquals(1, containing.size());
            assertEquals(1L, containing.getFirst().getId());
            assertEquals(1, in.size());
            assertEquals("测试用户", in.getFirst().getUsername());
        }
    }

    @Test
    void 应执行S9生成的动态条件更新与统计() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);

            assertEquals(1, mapper.findByUsernameAndEmail(
                    null, "test@example.com").size());
            assertEquals(1L, mapper.countByUsername("测试用户"));
            assertEquals(1, mapper.updateEmailById("updated@example.com", 1L));
            assertEquals("updated@example.com", mapper.findById(1L).getEmail());
        }
    }

    @Test
    void 应以单条Statement完成批量插入且无效输入在动态Sql绑定阶段失败() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            long before = mapper.countByUsername("批量用户甲")
                    + mapper.countByUsername("批量用户乙");
            List<User> users = List.of(
                    new User(null, "批量用户甲", "batch-a@example.com"),
                    new User(null, "批量用户乙", "batch-b@example.com"));

            assertEquals(2, mapper.insertBatch(users));
            assertThrows(RuntimeException.class, () -> mapper.insertBatch(List.of()));
            assertThrows(RuntimeException.class, () -> mapper.insertBatch(null));
            assertThrows(RuntimeException.class, () -> mapper.insertBatch(
                    java.util.Arrays.asList(
                            new User(null, "不得写入", "blocked@example.com"), null)));
            assertEquals(before + 2,
                    mapper.countByUsername("批量用户甲")
                            + mapper.countByUsername("批量用户乙"));
            assertTrue(users.stream().allMatch(user -> user.getId() == null));
            session.rollback();
        }
    }

    @Test
    void 空集合和Null集合的更新删除必须零写入() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User before = mapper.findById(1L);
            Collection<Long> containsNull = java.util.Arrays.asList(1L, null);

            assertEquals(0, mapper.updateEmailByIdIn(
                    "must-not-write@example.com", List.of()));
            assertEquals(0, mapper.updateEmailByIdIn(
                    "must-not-write@example.com", null));
            assertEquals(0, mapper.updateEmailByIdIn(
                    "must-not-write@example.com", containsNull));
            assertEquals(0, mapper.deleteByIdIn(List.of()));
            assertEquals(0, mapper.deleteByIdIn((Collection<Long>) null));
            assertEquals(0, mapper.deleteByIdIn(containsNull));
            assertTrue(mapper.findByIdInOrUsername(
                    List.of(), "测试用户").isEmpty());
            assertTrue(mapper.findByIdInOrUsername(
                    null, "测试用户").isEmpty());
            assertTrue(mapper.findByIdInOrUsername(
                    containsNull, "测试用户").isEmpty());
            assertEquals(0, mapper.updateEmailByIdInOrUsername(
                    "must-not-write@example.com", List.of(), "测试用户"));
            assertEquals(0, mapper.updateEmailByIdInOrUsername(
                    "must-not-write@example.com", null, "测试用户"));
            assertEquals(0, mapper.updateEmailByIdInOrUsername(
                    "must-not-write@example.com", containsNull, "测试用户"));
            assertTrue(mapper.findByIdIn(containsNull).isEmpty());

            User after = mapper.findById(1L);
            assertNotNull(after);
            assertEquals(before.getEmail(), after.getEmail());
            session.rollback();
        }
    }
}
