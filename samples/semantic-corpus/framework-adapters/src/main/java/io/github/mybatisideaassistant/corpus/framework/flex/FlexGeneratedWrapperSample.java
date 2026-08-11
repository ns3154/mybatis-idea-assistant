package io.github.mybatisideaassistant.corpus.framework.flex;

import com.mybatisflex.core.query.QueryMethods;
import com.mybatisflex.core.query.QueryWrapper;

/**
 * 验证 S9 生成器使用的 MyBatis-Flex 1.7.2+ QueryWrapper API 可编译。
 */
public final class FlexGeneratedWrapperSample {
    private FlexGeneratedWrapperSample() {
    }

    public static QueryWrapper query(String name, Long id, int pageSize, long offset) {
        QueryWrapper wrapper = QueryWrapper.create().from("users");
        wrapper.select(QueryMethods.distinct(QueryMethods.column("name")));
        wrapper.likeLeft("name", name, name != null).gt("id", id);
        wrapper.orderBy(QueryMethods.column("id").desc());
        wrapper.limit(pageSize).offset(offset);
        return wrapper;
    }
}
