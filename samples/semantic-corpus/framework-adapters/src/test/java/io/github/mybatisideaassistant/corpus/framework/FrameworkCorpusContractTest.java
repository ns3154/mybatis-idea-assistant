package io.github.mybatisideaassistant.corpus.framework;

import io.github.mybatisideaassistant.corpus.framework.flex.FlexUserMapper;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusUserMapper;
import io.github.mybatisideaassistant.corpus.framework.tk.TkUserMapper;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class FrameworkCorpusContractTest {
    @Test
    public void keepsAllFrameworkMapperTypesResolvable() {
        assertTrue(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.isAssignableFrom(PlusUserMapper.class));
        assertTrue(com.mybatisflex.core.BaseMapper.class.isAssignableFrom(FlexUserMapper.class));
        assertTrue(tk.mybatis.mapper.common.Mapper.class.isAssignableFrom(TkUserMapper.class));
    }
}
