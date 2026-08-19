package io.github.mybatisideaassistant.corpus.framework.plus;

/**
 * 由当前 MyBatisWrapperGenerator 直接导出，并由 Maven 使用锁定框架版本编译。
 */
public final class PlusGeneratedWrapperSample {
    private PlusGeneratedWrapperSample() {
    }

    public static com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> collectionsAndRange(
            java.util.Collection<java.lang.Integer> rankValueValues,
            java.util.Collection<java.lang.String> statusValues,
            java.lang.Integer ageStart,
            java.lang.Integer ageEnd) {
        if (rankValueValues == null || rankValueValues.isEmpty() || java.util.Collections.frequency(rankValueValues, null) != 0 || statusValues == null || statusValues.isEmpty() || java.util.Collections.frequency(statusValues, null) != 0 || ageStart == null || ageEnd == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.in("\"order\"", rankValueValues).notIn("\"status\"", statusValues).between("\"age\"", ageStart, ageEnd);
        return wrapper;
    }

    public static com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> singleOr(
            java.lang.String status,
            java.lang.Integer age) {
        if (status == null || age == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("\"status\"", status).or().gt("\"age\"", age);
        return wrapper;
    }

    public static com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> multiOr(
            java.lang.String status,
            java.lang.Integer age) {
        if (status == null || age == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("\"status\"", status).or(group1 -> group1.gt("\"age\"", age).eq("\"active\"", true));
        return wrapper;
    }

    public static com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> parameterNameConflicts(
            java.lang.String wrapper,
            java.lang.String group1) {
        if (wrapper == null || group1 == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<io.github.mybatisideaassistant.corpus.framework.plus.PlusUser> wrapper2 = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper2.eq("\"wrapper_value\"", wrapper).or(group2 -> group2.eq("\"group_value\"", group1).eq("\"active\"", true));
        return wrapper2;
    }
}
