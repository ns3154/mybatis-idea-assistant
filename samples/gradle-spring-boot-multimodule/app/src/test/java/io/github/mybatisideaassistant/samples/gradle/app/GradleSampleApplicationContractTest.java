package io.github.mybatisideaassistant.samples.gradle.app;

import io.github.mybatisideaassistant.samples.gradle.api.UserRepository;
import io.github.mybatisideaassistant.samples.gradle.data.mapper.UserMapper;
import io.github.mybatisideaassistant.samples.gradle.domain.User;
import io.github.mybatisideaassistant.samples.gradle.domain.UserCriteria;
import io.github.mybatisideaassistant.samples.gradle.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GradleSampleApplicationContractTest {
    @Autowired
    private UserMapper userMapper;

    @Test
    void loadsMapperXmlFromDataModuleAndQueriesEmbeddedDatabase() {
        User user = userMapper.findById(1L);
        assertNotNull(user);
        assertEquals("张三示例", user.getName());
        assertEquals(UserStatus.ACTIVE, user.getStatus());

        List<User> activeUsers = userMapper.find(new UserCriteria("示例", UserStatus.ACTIVE));
        assertEquals(List.of(1L), activeUsers.stream().map(User::getId).toList());
        assertInstanceOf(UserRepository.class, userMapper);
    }
}
