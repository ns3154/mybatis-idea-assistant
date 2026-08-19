package io.github.ns3154.mybatisassistant.model;

/**
 * MyBatis 传给 SQL 节点的是原始单参数对象，还是具名参数 Map。
 */
public enum MyBatisParameterRootMode {
    DIRECT,
    NAMED
}
