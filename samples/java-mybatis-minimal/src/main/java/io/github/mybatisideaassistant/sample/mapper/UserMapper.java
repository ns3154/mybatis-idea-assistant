package io.github.mybatisideaassistant.sample.mapper;

import io.github.mybatisideaassistant.sample.domain.User;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

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

    /**
     * S9 set-based 批量插入：单条插入 statement，无效集合在动态 SQL 绑定阶段失败。
     */
    int insertBatch(@Param("entities") Collection<User> entities);

    /**
     * S9 方法名语法生成样例：包含字符串、比较和排序。
     */
    List<User> findByUsernameContainingAndIdGreaterThanOrderByIdDesc(
            @Param("username") String username,
            @Param("id") Long id);

    /**
     * S9 方法名语法生成样例：集合条件使用安全 foreach。
     */
    List<User> findByIdIn(@Param("idValues") Collection<Long> idValues);

    /**
     * S9 复合 OR 语料：必填集合为空时必须让整个谓词 fail-closed。
     */
    List<User> findByIdInOrUsername(
            @Param("idValues") Collection<Long> idValues,
            @Param("username") String username);

    /**
     * S9 动态条件样例：空参数只省略对应条件。
     */
    List<User> findByUsernameAndEmail(
            @Param("username") String username,
            @Param("email") String email);

    /**
     * S9 方法名语法生成样例：更新值与条件参数独立命名。
     */
    int updateEmailById(
            @Param("newEmail") String newEmail,
            @Param("id") Long id);

    /**
     * S9 集合更新：null/空集合必须通过 1=0 保证零写入。
     */
    int updateEmailByIdIn(
            @Param("newEmail") String newEmail,
            @Param("idValues") Collection<Long> idValues);

    /**
     * S9 复合 OR 更新：必填集合为空时不能让另一分支扩大写入范围。
     */
    int updateEmailByIdInOrUsername(
            @Param("newEmail") String newEmail,
            @Param("idValues") Collection<Long> idValues,
            @Param("username") String username);

    /**
     * S9 集合删除：null/空集合必须通过 1=0 保证零写入。
     */
    int deleteByIdIn(@Param("idValues") Collection<Long> idValues);

    /**
     * S9 方法名语法生成样例：统计返回 long。
     */
    long countByUsername(@Param("username") String username);
}
