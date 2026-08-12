package io.github.mybatisideaassistant.samples.gradle.domain;

/**
 * 不依赖持久化框架的用户领域对象。
 */
public final class User {
    private long id;
    private String name;
    private UserStatus status;

    public User() {
    }

    public User(long id, String name, UserStatus status) {
        this.id = id;
        this.name = name;
        this.status = status;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }
}
