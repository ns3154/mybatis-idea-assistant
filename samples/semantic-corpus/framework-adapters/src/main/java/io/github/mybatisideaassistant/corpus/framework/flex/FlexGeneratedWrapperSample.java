package io.github.mybatisideaassistant.corpus.framework.flex;

/**
 * 由当前 MyBatisWrapperGenerator 直接导出，并由 Maven 使用锁定框架版本编译。
 */
public final class FlexGeneratedWrapperSample {
    private FlexGeneratedWrapperSample() {
    }

    public static com.mybatisflex.core.query.QueryWrapper collectionsAndRange(
            java.util.Collection<java.lang.Integer> rankValueValues,
            java.util.Collection<java.lang.String> statusValues,
            java.lang.Integer ageStart,
            java.lang.Integer ageEnd) {
        if (rankValueValues == null || rankValueValues.isEmpty() || java.util.Collections.frequency(rankValueValues, null) != 0 || statusValues == null || statusValues.isEmpty() || java.util.Collections.frequency(statusValues, null) != 0 || ageStart == null || ageEnd == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.mybatisflex.core.query.QueryWrapper wrapper = com.mybatisflex.core.query.QueryWrapper.create().from(new com.mybatisflex.core.query.RawQueryTable("\"audit\".\"users\""));
        wrapper.in("\"order\"", rankValueValues, true).notIn("\"status\"", statusValues, true).between("\"age\"", ageStart, ageEnd, true);
        return wrapper;
    }

    public static com.mybatisflex.core.query.QueryWrapper singleOr(
            java.lang.String status,
            java.lang.Integer age) {
        if (status == null || age == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.mybatisflex.core.query.QueryWrapper wrapper = com.mybatisflex.core.query.QueryWrapper.create().from(new com.mybatisflex.core.query.RawQueryTable("\"audit\".\"users\""));
        wrapper.eq("\"status\"", status, true).or((java.util.function.Consumer<com.mybatisflex.core.query.QueryWrapper>) group1 -> group1.gt("\"age\"", age, true));
        return wrapper;
    }

    public static com.mybatisflex.core.query.QueryWrapper multiOr(
            java.lang.String status,
            java.lang.Integer age) {
        if (status == null || age == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.mybatisflex.core.query.QueryWrapper wrapper = com.mybatisflex.core.query.QueryWrapper.create().from(new com.mybatisflex.core.query.RawQueryTable("\"audit\".\"users\""));
        wrapper.eq("\"status\"", status, true).or((java.util.function.Consumer<com.mybatisflex.core.query.QueryWrapper>) group1 -> group1.gt("\"age\"", age, true).eq("\"active\"", true, true));
        return wrapper;
    }

    public static com.mybatisflex.core.query.QueryWrapper parameterNameConflicts(
            java.lang.String wrapper,
            java.lang.String group1) {
        if (wrapper == null || group1 == null) {
            throw new IllegalArgumentException("Wrapper 条件参数无效：必填标量和区间端点不能为 null，必填集合不能为 null、空或包含 null，可选非空集合也不能包含 null");
        }
        com.mybatisflex.core.query.QueryWrapper wrapper2 = com.mybatisflex.core.query.QueryWrapper.create().from(new com.mybatisflex.core.query.RawQueryTable("\"audit\".\"users\""));
        wrapper2.eq("\"wrapper_value\"", wrapper, true).or((java.util.function.Consumer<com.mybatisflex.core.query.QueryWrapper>) group2 -> group2.eq("\"group_value\"", group1, true).eq("\"active\"", true, true));
        return wrapper2;
    }
}
