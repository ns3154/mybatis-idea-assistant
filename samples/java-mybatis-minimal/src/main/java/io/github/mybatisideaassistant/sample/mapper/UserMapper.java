package io.github.mybatisideaassistant.sample.mapper;

import io.github.mybatisideaassistant.sample.domain.User;
import org.apache.ibatis.annotations.Param;

/**
 * 正常关联语料：接口全限定名与 XML namespace 完全一致。
 */
public interface UserMapper {

    /**
     * 按主键查询用户，对应 XML 中同名的 select statement。
     */
    User findById(@Param("id") long id);

    /**
     * 新增用户，对应 XML 中同名的 insert statement。
     */
    int insert(User user);
}
