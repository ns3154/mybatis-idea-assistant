package io.github.ns3154.mybatisassistant.ognl;

/**
 * OGNL 源文本中的半开范围。
 */
public record MyBatisOgnlRange(int startOffset, int endOffset) {
    public MyBatisOgnlRange {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("OGNL 范围必须满足 0 <= start <= end");
        }
    }

    public int length() {
        return endOffset - startOffset;
    }

    public static MyBatisOgnlRange spanning(
            MyBatisOgnlRange left,
            MyBatisOgnlRange right) {
        return new MyBatisOgnlRange(left.startOffset(), right.endOffset());
    }
}
