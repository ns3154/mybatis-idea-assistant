package io.github.ns3154.mybatisassistant.ognl;

/**
 * OGNL 静态语义解析状态；未知与生命周期失败不会冒充确定缺失。
 */
public enum MyBatisOgnlSemanticStatus {
    FOUND,
    UNKNOWN,
    DEFINITE_MISSING,
    INDEX_NOT_READY,
    SOURCE_INVALID,
    UNSUPPORTED_SOURCE
}
