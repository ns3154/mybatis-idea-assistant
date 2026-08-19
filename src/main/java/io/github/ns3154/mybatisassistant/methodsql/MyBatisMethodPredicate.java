package io.github.ns3154.mybatisassistant.methodsql;

/**
 * 不执行用户代码的不可变条件 AST。
 */
public sealed interface MyBatisMethodPredicate
        permits MyBatisMethodCondition, MyBatisMethodJunction {
}
