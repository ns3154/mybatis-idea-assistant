package io.github.mybatisideaassistant.corpus.framework.plus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface PlusUserMapper extends BaseMapper<PlusUser> {
    default List<PlusUser> findNamed(String name) {
        return selectList(new LambdaQueryWrapper<PlusUser>().eq(PlusUser::getName, name));
    }
}
