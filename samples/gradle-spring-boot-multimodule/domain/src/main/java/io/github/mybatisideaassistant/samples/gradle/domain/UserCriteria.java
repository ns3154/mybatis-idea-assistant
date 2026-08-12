package io.github.mybatisideaassistant.samples.gradle.domain;

/**
 * 用户查询条件；空字段表示不限制该维度。
 */
public final class UserCriteria {
    private final String name;
    private final UserStatus status;

    public UserCriteria(String name, UserStatus status) {
        this.name = name;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public UserStatus getStatus() {
        return status;
    }
}
