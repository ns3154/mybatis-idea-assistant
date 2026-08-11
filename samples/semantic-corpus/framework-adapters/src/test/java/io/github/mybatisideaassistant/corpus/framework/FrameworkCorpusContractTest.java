package io.github.mybatisideaassistant.corpus.framework;

import io.github.mybatisideaassistant.corpus.framework.flex.FlexUserMapper;
import io.github.mybatisideaassistant.corpus.framework.flex.FlexGeneratedWrapperSample;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusGeneratedWrapperSample;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusUserMapper;
import io.github.mybatisideaassistant.corpus.framework.tk.TkUserMapper;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

public class FrameworkCorpusContractTest {
    @Test
    public void keepsAllFrameworkMapperTypesResolvable() {
        assertTrue(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.isAssignableFrom(PlusUserMapper.class));
        assertTrue(com.mybatisflex.core.BaseMapper.class.isAssignableFrom(FlexUserMapper.class));
        assertTrue(tk.mybatis.mapper.common.Mapper.class.isAssignableFrom(TkUserMapper.class));
    }

    @Test
    public void keepsGeneratedWrapperApisCompilable() {
        assertNotNull(PlusGeneratedWrapperSample.query("yang", 1L));
        assertNotNull(PlusGeneratedWrapperSample.update("newName", 1L));
        assertNotNull(FlexGeneratedWrapperSample.query("yang", 1L, 20, 0L));
    }
}
