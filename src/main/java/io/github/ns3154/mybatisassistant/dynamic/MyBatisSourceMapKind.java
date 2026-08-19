package io.github.ns3154.mybatisassistant.dynamic;

/**
 * 虚拟 SQL 与原始 XML 的字符关系。
 */
public enum MyBatisSourceMapKind {
    /** 虚拟字符与源字符逐个等长对应。 */
    EXACT,
    /** XML entity 等解码文本，一个虚拟范围对应一个较长源范围。 */
    DECODED,
    /** prefix、separator 等编译器合成文本，只关联来源而不声称字符等价。 */
    SYNTHETIC
}
