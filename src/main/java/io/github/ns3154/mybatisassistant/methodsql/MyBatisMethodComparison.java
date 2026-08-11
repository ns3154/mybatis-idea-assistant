package io.github.ns3154.mybatisassistant.methodsql;

/**
 * 条件操作符及其需要的方法参数数量。
 */
public enum MyBatisMethodComparison {
    EQUALS(1),
    NOT_EQUALS(1),
    LESS_THAN(1),
    LESS_THAN_OR_EQUAL(1),
    GREATER_THAN(1),
    GREATER_THAN_OR_EQUAL(1),
    BETWEEN(2),
    IN(1),
    NOT_IN(1),
    LIKE(1),
    NOT_LIKE(1),
    STARTING_WITH(1),
    ENDING_WITH(1),
    CONTAINING(1),
    IS_NULL(0),
    IS_NOT_NULL(0),
    TRUE(0),
    FALSE(0);

    private final int parameterCount;

    MyBatisMethodComparison(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    public int parameterCount() {
        return parameterCount;
    }
}
