package io.github.mybatisideaassistant.samples.gradle.api;

import io.github.mybatisideaassistant.samples.gradle.domain.User;
import io.github.mybatisideaassistant.samples.gradle.domain.UserCriteria;

import java.util.List;

/**
 * 用户查询契约，由数据模块提供 MyBatis 实现。
 */
public interface UserRepository {
    User findById(long id);

    List<User> find(UserCriteria criteria);
}
