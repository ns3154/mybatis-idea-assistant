package io.github.ns3154.mybatisassistant.ognl;

/**
 * 归一化后的 OGNL 运算符；关键字和符号写法共享同一语义。
 */
public enum MyBatisOgnlOperator {
    POSITIVE,
    NEGATIVE,
    NOT,
    MULTIPLY,
    DIVIDE,
    REMAINDER,
    ADD,
    SUBTRACT,
    LESS,
    LESS_OR_EQUAL,
    GREATER,
    GREATER_OR_EQUAL,
    IN,
    NOT_IN,
    INSTANCE_OF,
    EQUAL,
    NOT_EQUAL,
    AND,
    OR
}
