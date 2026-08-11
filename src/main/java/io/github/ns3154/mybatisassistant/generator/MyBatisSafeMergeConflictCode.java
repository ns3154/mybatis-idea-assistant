package io.github.ns3154.mybatisassistant.generator;

/**
 * 生成合并在写入前可定位的冲突类型。
 */
public enum MyBatisSafeMergeConflictCode {
    FILE_WITHOUT_MARKERS,
    MALFORMED_MARKERS,
    MARKER_SET_CHANGED,
    GENERATED_REGION_MODIFIED,
    PATH_COLLISION,
    TARGET_READ_ONLY,
    TARGET_IS_DIRECTORY,
    IO_ERROR,
    INVALID_GENERATED_CONTENT,
    SOURCE_CHANGED_AFTER_PREVIEW
}
