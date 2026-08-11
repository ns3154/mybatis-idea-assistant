package io.github.ns3154.mybatisassistant.dynamic;

/**
 * 动态 SQL 词法绑定来源。
 */
public enum MyBatisDynamicSqlBindingKind {
    FOREACH_ITEM,
    FOREACH_INDEX,
    BIND,
    INCLUDE_PROPERTY
}
