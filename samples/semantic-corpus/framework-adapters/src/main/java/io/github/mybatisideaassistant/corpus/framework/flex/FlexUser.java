package io.github.mybatisideaassistant.corpus.framework.flex;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("users")
public class FlexUser {
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
