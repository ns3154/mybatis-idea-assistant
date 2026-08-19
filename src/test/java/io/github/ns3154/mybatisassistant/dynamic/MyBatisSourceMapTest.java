package io.github.ns3154.mybatisassistant.dynamic;

import junit.framework.TestCase;

import java.util.List;

public final class MyBatisSourceMapTest extends TestCase {
    private static final String FILE_URL = "file:///project/UserMapper.xml";

    public void testExactDecodedAndSyntheticMappingsAreBidirectional() {
        MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
        builder.appendExact("select", FILE_URL, 10);
        builder.appendDecoded("<", FILE_URL, new MyBatisTextRange(20, 24));
        builder.appendSynthetic(" WHERE ", FILE_URL, new MyBatisTextRange(30, 37));

        MyBatisMappedText mapped = builder.build();

        assertEquals("select< WHERE ", mapped.text());
        assertEquals(3, mapped.sourceMap().segments().size());
        assertEquals(
                new MyBatisSourceMapping(
                        new MyBatisTextRange(2, 5),
                        new MyBatisSourceRange(FILE_URL, new MyBatisTextRange(12, 15)),
                        MyBatisSourceMapKind.EXACT),
                mapped.sourceMap().sourceMappings(new MyBatisTextRange(2, 5)).getFirst());
        assertEquals(
                MyBatisSourceMapKind.DECODED,
                mapped.sourceMap().sourceMappings(new MyBatisTextRange(6, 7)).getFirst().kind());
        assertEquals(
                new MyBatisTextRange(20, 24),
                mapped.sourceMap().sourceMappings(
                        new MyBatisTextRange(6, 7)).getFirst().sourceRange().range());
        assertEquals(
                new MyBatisTextRange(6, 7),
                mapped.sourceMap().virtualMappings(new MyBatisSourceRange(
                        FILE_URL,
                        new MyBatisTextRange(22, 23))).getFirst().virtualRange());
        assertEquals(
                MyBatisSourceMapKind.SYNTHETIC,
                mapped.sourceMap().virtualMappings(new MyBatisSourceRange(
                        FILE_URL,
                        new MyBatisTextRange(31, 32))).getFirst().kind());
    }

    public void testAdjacentExactSegmentsAreCompressed() {
        MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
        builder.appendExact("select ", FILE_URL, 5);
        builder.appendExact("1", FILE_URL, 12);

        MyBatisMappedText mapped = builder.build();

        assertEquals("select 1", mapped.text());
        assertEquals(1, mapped.sourceMap().segments().size());
        assertEquals(new MyBatisTextRange(5, 13),
                mapped.sourceMap().segments().getFirst().sourceRange().range());
    }

    public void testQueriesRejectOutOfBoundsAndIgnoreOtherFiles() {
        MyBatisSourceMapBuilder builder = new MyBatisSourceMapBuilder();
        builder.appendExact("sql", FILE_URL, 3);
        MyBatisSourceMap map = builder.build().sourceMap();

        assertEmpty(map.virtualMappings(new MyBatisSourceRange(
                "file:///project/Other.xml",
                new MyBatisTextRange(3, 4))));
        assertIllegalArgument(() -> map.sourceMappings(new MyBatisTextRange(0, 4)));
        assertIllegalArgument(() -> new MyBatisSourceMap(
                2,
                List.of(new MyBatisSourceMapSegment(
                        new MyBatisTextRange(1, 2),
                        new MyBatisSourceRange(FILE_URL, new MyBatisTextRange(1, 2)),
                        MyBatisSourceMapKind.EXACT))));
    }

    private static void assertEmpty(List<?> values) {
        assertTrue(values.isEmpty());
    }

    private static void assertIllegalArgument(Runnable operation) {
        try {
            operation.run();
            fail("无效范围必须抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 模型不变量要求在构造或查询边界立即失败。
        }
    }
}
