package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

/**
 * 当前交付矩阵明确支持的 Mapper 框架与 API 边界。
 */
public enum MyBatisFrameworkKind {
    MYBATIS_PLUS(
            "MyBatis-Plus",
            "com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "model.framework.range.mybatis.plus"),
    MYBATIS_FLEX(
            "MyBatis-Flex",
            "com.mybatisflex.core.BaseMapper",
            "model.framework.range.mybatis.flex"),
    TK_MAPPER(
            "TkMapper",
            "tk.mybatis.mapper.common.Mapper",
            "model.framework.range.tk.mapper");

    private final String displayName;
    private final String baseMapperQualifiedName;
    private final String supportedVersionRangeKey;

    MyBatisFrameworkKind(
            @NotNull String displayName,
            @NotNull String baseMapperQualifiedName,
            @NotNull String supportedVersionRangeKey) {
        this.displayName = displayName;
        this.baseMapperQualifiedName = baseMapperQualifiedName;
        this.supportedVersionRangeKey = supportedVersionRangeKey;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public @NotNull String baseMapperQualifiedName() {
        return baseMapperQualifiedName;
    }

    public @NotNull String supportedVersionRange() {
        return MyBatisModelMessages.message(supportedVersionRangeKey);
    }

    /**
     * 校验用户显式提供的框架版本，不从类路径文件名猜测版本。
     */
    public void requireSupportedVersion(@NotNull String version) {
        Version parsed = Version.parse(version);
        boolean supported = switch (this) {
            case MYBATIS_PLUS -> parsed.major() == 3 && parsed.minor() >= 5;
            case MYBATIS_FLEX -> parsed.major() == 1
                    && (parsed.minor() > 7
                    || parsed.minor() == 7 && parsed.patch() >= 2);
            case TK_MAPPER -> parsed.major() == 6;
        };
        if (!supported) {
            throw new IllegalArgumentException(MyBatisModelMessages.message(
                    "model.error.framework.version.unsupported",
                    displayName, version, supportedVersionRange()));
        }
    }

    private record Version(int major, int minor, int patch) {
        private static @NotNull Version parse(@NotNull String value) {
            if (!value.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) {
                throw new IllegalArgumentException(MyBatisModelMessages.message(
                        "model.error.framework.version.format", value));
            }
            String[] parts = value.split("\\.");
            try {
                return new Version(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        parts.length == 3 ? Integer.parseInt(parts[2]) : 0);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(MyBatisModelMessages.message(
                        "model.error.framework.version.number", value), invalid);
            }
        }
    }
}
