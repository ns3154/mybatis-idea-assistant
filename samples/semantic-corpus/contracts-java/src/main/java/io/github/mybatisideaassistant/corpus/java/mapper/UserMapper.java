package io.github.mybatisideaassistant.corpus.java.mapper;

import io.github.mybatisideaassistant.corpus.java.domain.User;
import io.github.mybatisideaassistant.corpus.java.domain.UserState;
import io.github.mybatisideaassistant.corpus.java.query.PageRequest;
import io.github.mybatisideaassistant.corpus.java.query.UserFilter;
import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

public interface UserMapper extends ParentMapper<User, Long> {
    User findById(long id);

    List<User> findByNameAndState(@Param("name") String name, @Param("state") UserState state);

    List<User> findByIds(@Param("ids") List<Long> ids);

    List<User> findByAttributes(Map<String, Object> attributes);

    List<User> findPage(PageRequest<UserFilter> page);

    int insertUser(User user);

    int updateUser(@Param("user") User user, @Param("changedFields") List<String> changedFields);

    int deleteByIds(@Param("ids") long[] ids);

    @Select("select id, name, state from users where id = #{id}")
    User findAnnotated(long id);

    @SelectProvider(type = UserSqlProvider.class, method = "selectActive")
    List<User> findProvided();

    @Flush
    List<Object> flushStatements();
}
