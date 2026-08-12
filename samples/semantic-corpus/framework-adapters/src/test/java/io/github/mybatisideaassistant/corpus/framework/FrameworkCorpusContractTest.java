package io.github.mybatisideaassistant.corpus.framework;

import io.github.mybatisideaassistant.corpus.framework.flex.FlexGeneratedWrapperSample;
import io.github.mybatisideaassistant.corpus.framework.flex.FlexUser;
import io.github.mybatisideaassistant.corpus.framework.flex.FlexUserMapper;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusGeneratedWrapperSample;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusUser;
import io.github.mybatisideaassistant.corpus.framework.plus.PlusUserMapper;
import io.github.mybatisideaassistant.corpus.framework.tk.TkUser;
import io.github.mybatisideaassistant.corpus.framework.tk.TkUserMapper;
import org.junit.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void locksBaseMapperShapesAndEntityBindings() {
        assertBaseContract(
                com.baomidou.mybatisplus.core.mapper.BaseMapper.class,
                "insert",
                "selectById");
        assertBaseContract(
                com.mybatisflex.core.BaseMapper.class,
                "insert",
                "selectOneById");
        assertBaseContract(
                tk.mybatis.mapper.common.Mapper.class,
                "insert",
                "selectByPrimaryKey");
        assertEntityBinding(PlusUserMapper.class, PlusUser.class);
        assertEntityBinding(FlexUserMapper.class, FlexUser.class);
        assertEntityBinding(TkUserMapper.class, TkUser.class);
    }

    private static void assertBaseContract(
            Class<?> baseMapper,
            String writeMethod,
            String readMethod) {
        assertEquals(1, baseMapper.getTypeParameters().length);
        assertTrue(Arrays.stream(baseMapper.getMethods())
                .anyMatch(method -> writeMethod.equals(method.getName())));
        assertTrue(Arrays.stream(baseMapper.getMethods())
                .anyMatch(method -> readMethod.equals(method.getName())));
    }

    private static void assertEntityBinding(Class<?> mapper, Class<?> entity) {
        Type genericBase = mapper.getGenericInterfaces()[0];
        assertTrue(genericBase instanceof ParameterizedType);
        assertEquals(entity, ((ParameterizedType) genericBase).getActualTypeArguments()[0]);
    }
}
