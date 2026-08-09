package io.github.mybatisideaassistant.sample.domain;

/**
 * 最小用户实体，用于验证列名到 Java 属性的映射。
 */
public class User {

    private Long id;
    private String username;
    private String email;

    public User() {
        // MyBatis 通过无参构造器创建实体。
    }

    public User(Long id, String username, String email) {
        this.id = id;
        this.username = username;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
