package io.github.ns3154.mybatisassistant.generator;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 带内容指纹的稳定生成区，指纹不匹配时必须停止合并。
 */
public final class MyBatisGeneratedRegion {
    public enum Style {
        JAVA,
        XML
    }

    private MyBatisGeneratedRegion() {
    }

    public static @NotNull String render(
            @NotNull Style style,
            @NotNull String id,
            @NotNull String body) {
        if (id.isBlank() || id.indexOf('"') >= 0 || id.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("生成区标识不合法");
        }
        String normalized = normalize(body);
        String start = switch (style) {
            case JAVA -> "// <mybatis-assistant-generated id=\"" + id
                    + "\" sha256=\"" + sha256(normalized) + "\">";
            case XML -> "<!-- <mybatis-assistant-generated id=\"" + id
                    + "\" sha256=\"" + sha256(normalized) + "\"> -->";
        };
        String end = switch (style) {
            case JAVA -> "// </mybatis-assistant-generated id=\"" + id + "\">";
            case XML -> "<!-- </mybatis-assistant-generated id=\"" + id + "\"> -->";
        };
        return start + "\n" + normalized + end + "\n";
    }

    static @NotNull String sha256(@NotNull String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    static @NotNull String normalize(@NotNull String body) {
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }
}
