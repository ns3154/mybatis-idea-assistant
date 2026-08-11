package io.github.mybatisideaassistant.corpus.framework.plus;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

/**
 * 验证 S9 生成器使用的 MyBatis-Plus 3.5 Wrapper API 可编译。
 */
public final class PlusGeneratedWrapperSample {
    private PlusGeneratedWrapperSample() {
    }

    public static QueryWrapper<PlusUser> query(String name, Long id) {
        QueryWrapper<PlusUser> wrapper = new QueryWrapper<>();
        wrapper.select("name");
        wrapper.likeRight(name != null, "name", name).gt("id", id);
        wrapper.orderByDesc("id");
        return wrapper;
    }

    public static UpdateWrapper<PlusUser> update(String newName, Long id) {
        UpdateWrapper<PlusUser> wrapper = new UpdateWrapper<>();
        wrapper.set("name", newName);
        wrapper.eq("id", id);
        return wrapper;
    }
}
