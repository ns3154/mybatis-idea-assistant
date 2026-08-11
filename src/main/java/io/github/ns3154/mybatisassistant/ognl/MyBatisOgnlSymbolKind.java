package io.github.ns3154.mybatisassistant.ognl;

/**
 * 可出现在 OGNL 名称范围上的静态符号种类。
 */
public enum MyBatisOgnlSymbolKind {
    ROOT,
    CONTEXT_VARIABLE,
    PROPERTY,
    METHOD,
    STATIC_CLASS,
    STATIC_MEMBER
}
