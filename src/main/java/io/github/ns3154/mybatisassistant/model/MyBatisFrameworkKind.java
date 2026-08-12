package io.github.ns3154.mybatisassistant.model;

import org.jetbrains.annotations.NotNull;

/**
 * 当前交付矩阵明确支持的 Mapper 框架与 API 边界。
 */
public enum MyBatisFrameworkKind {
    MYBATIS_PLUS(
            "MyBatis-Plus",
            "com.baomidou.mybatisplus.core.mapper.BaseMapper",
            "3.5+ 的 3.x"),
    MYBATIS_FLEX(
            "MyBatis-Flex",
            "com.mybatisflex.core.BaseMapper",
            "1.7.2+ 的 1.x"),
    TK_MAPPER(
            "TkMapper",
            "tk.mybatis.mapper.common.Mapper",
            "6.x");

    private final String displayName;
    private final String baseMapperQualifiedName;
    private final String supportedVersionRange;

    MyBatisFrameworkKind(
            @NotNull String displayName,
            @NotNull String baseMapperQualifiedName,
            @NotNull String supportedVersionRange) {
        this.displayName = displayName;
        this.baseMapperQualifiedName = baseMapperQualifiedName;
        this.supportedVersionRange = supportedVersionRange;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public @NotNull String baseMapperQualifiedName() {
        return baseMapperQualifiedName;
    }

    public @NotNull String supportedVersionRange() {
        return supportedVersionRange;
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
            throw new IllegalArgumentException(
                    "不支持的 " + displayName + " 版本：" + version
                            + "；支持范围：" + supportedVersionRange);
        }
    }

    private record Version(int major, int minor, int patch) {
        private static @NotNull Version parse(@NotNull String value) {
            if (!value.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) {
                throw new IllegalArgumentException(
                        "框架版本格式必须为 major.minor[.patch]：" + value);
            }
            String[] parts = value.split("\\.");
            try {
                return new Version(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        parts.length == 3 ? Integer.parseInt(parts[2]) : 0);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("框架版本数字过大：" + value, invalid);
            }
        }
    }
}
