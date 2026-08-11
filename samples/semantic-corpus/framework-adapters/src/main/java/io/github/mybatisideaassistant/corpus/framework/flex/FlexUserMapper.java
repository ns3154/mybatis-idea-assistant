package io.github.mybatisideaassistant.corpus.framework.flex;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

public interface FlexUserMapper extends BaseMapper<FlexUser> {
    default List<FlexUser> findNamed(String name) {
        return selectListByQuery(QueryWrapper.create().from("users").where("name = ?", name));
    }
}
