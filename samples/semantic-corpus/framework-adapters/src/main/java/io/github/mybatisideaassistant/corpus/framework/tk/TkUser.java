package io.github.mybatisideaassistant.corpus.framework.tk;

import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Table(name = "users")
public class TkUser {
    @Id
    private Long id;
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
