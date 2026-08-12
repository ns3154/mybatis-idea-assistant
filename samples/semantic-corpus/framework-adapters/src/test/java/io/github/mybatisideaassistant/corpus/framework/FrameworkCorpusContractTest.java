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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
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
        assertNotNull(PlusGeneratedWrapperSample.collectionsAndRange(
                List.of(1), List.of("ACTIVE"), 18, 65));
        assertNotNull(PlusGeneratedWrapperSample.singleOr("ACTIVE", 18));
        assertNotNull(PlusGeneratedWrapperSample.multiOr("ACTIVE", 18));
        assertNotNull(PlusGeneratedWrapperSample.parameterNameConflicts(
                "WRAPPER", "GROUP"));
        assertNotNull(FlexGeneratedWrapperSample.collectionsAndRange(
                List.of(1), List.of("ACTIVE"), 18, 65));
        assertNotNull(FlexGeneratedWrapperSample.singleOr("ACTIVE", 18));
        assertNotNull(FlexGeneratedWrapperSample.multiOr("ACTIVE", 18));
        assertNotNull(FlexGeneratedWrapperSample.parameterNameConflicts(
                "WRAPPER", "GROUP"));
    }

    @Test
    public void failsClosedBeforeCallingLockedWrapperApis() {
        for (java.util.Collection<Integer> invalid : List.of(
                List.<Integer>of(), Arrays.asList(1, null))) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlusGeneratedWrapperSample.collectionsAndRange(
                            invalid, List.of("ACTIVE"), 18, 65));
            assertThrows(IllegalArgumentException.class,
                    () -> FlexGeneratedWrapperSample.collectionsAndRange(
                            invalid, List.of("ACTIVE"), 18, 65));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PlusGeneratedWrapperSample.collectionsAndRange(
                        null, List.of("ACTIVE"), 18, 65));
        assertThrows(IllegalArgumentException.class,
                () -> FlexGeneratedWrapperSample.collectionsAndRange(
                        null, List.of("ACTIVE"), 18, 65));
        assertThrows(IllegalArgumentException.class,
                () -> PlusGeneratedWrapperSample.singleOr(null, 18));
        assertThrows(IllegalArgumentException.class,
                () -> FlexGeneratedWrapperSample.singleOr(null, 18));
        assertThrows(IllegalArgumentException.class,
                () -> FlexGeneratedWrapperSample.multiOr("ACTIVE", null));
        assertThrows(IllegalArgumentException.class,
                () -> PlusGeneratedWrapperSample.parameterNameConflicts(null, "GROUP"));
        assertThrows(IllegalArgumentException.class,
                () -> FlexGeneratedWrapperSample.parameterNameConflicts("WRAPPER", null));
    }

    @Test
    public void keepsRequiredFlexConditionsUnderGlobalIgnorePolicy() {
        java.util.function.Predicate<Object> original =
                com.mybatisflex.core.query.QueryColumnBehavior.getIgnoreFunction();
        try {
            com.mybatisflex.core.query.QueryColumnBehavior.setIgnoreFunction(value -> true);
            assertTrue(FlexGeneratedWrapperSample.collectionsAndRange(
                    List.of(1), List.of("ACTIVE"), 18, 65).hasCondition());
            assertTrue(FlexGeneratedWrapperSample.singleOr("ACTIVE", 18).hasCondition());
            assertTrue(FlexGeneratedWrapperSample.multiOr("ACTIVE", 18).hasCondition());
            assertTrue(FlexGeneratedWrapperSample.parameterNameConflicts(
                    "WRAPPER", "GROUP").hasCondition());
        } finally {
            com.mybatisflex.core.query.QueryColumnBehavior.setIgnoreFunction(original);
        }
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
