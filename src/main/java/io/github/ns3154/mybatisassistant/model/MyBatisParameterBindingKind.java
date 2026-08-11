package io.github.ns3154.mybatisassistant.model;

/**
 * MyBatis 运行期参数映射中一个可用名称的来源。
 */
public enum MyBatisParameterBindingKind {
    EXPLICIT,
    ACTUAL_NAME,
    GENERIC_NAME,
    REFLECTION_FALLBACK,
    NUMERIC_FALLBACK,
    PARAMETER_OBJECT,
    COLLECTION,
    LIST,
    ARRAY
}
