package io.github.ns3154.mybatisassistant.dynamic;

/**
 * 符号化动态 SQL 中间表示的节点基类。
 */
public sealed interface MyBatisDynamicSqlNode
        permits MyBatisSqlTextNode,
        MyBatisSqlSequenceNode,
        MyBatisIfNode,
        MyBatisChooseNode,
        MyBatisTrimNode,
        MyBatisForeachNode,
        MyBatisBindNode,
        MyBatisIncludeNode {
}
