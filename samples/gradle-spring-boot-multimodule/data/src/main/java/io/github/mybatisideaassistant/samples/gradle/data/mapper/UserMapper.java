package io.github.mybatisideaassistant.samples.gradle.data.mapper;

import io.github.mybatisideaassistant.samples.gradle.api.UserRepository;
import io.github.mybatisideaassistant.samples.gradle.domain.User;
import io.github.mybatisideaassistant.samples.gradle.domain.UserCriteria;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 使用 XML 完成 SQL 映射的数据访问接口。
 */
public interface UserMapper extends UserRepository {
    @Override
    User findById(@Param("id") long id);

    @Override
    List<User> find(@Param("criteria") UserCriteria criteria);
}
