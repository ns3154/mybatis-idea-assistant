package io.github.ns3154.mybatisassistant.methodsql;

import io.github.ns3154.mybatisassistant.model.MyBatisFrameworkKind;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * S9 首批显式支持的 Wrapper 框架。
 */
public enum MyBatisWrapperFramework {
    MYBATIS_PLUS(MyBatisFrameworkKind.MYBATIS_PLUS),
    MYBATIS_FLEX(MyBatisFrameworkKind.MYBATIS_FLEX);

    private final MyBatisFrameworkKind frameworkKind;

    MyBatisWrapperFramework(@NotNull MyBatisFrameworkKind frameworkKind) {
        this.frameworkKind = frameworkKind;
    }

    public @NotNull MyBatisFrameworkKind frameworkKind() {
        return frameworkKind;
    }

    public static @NotNull Optional<MyBatisWrapperFramework> fromFrameworkKind(
            @NotNull MyBatisFrameworkKind frameworkKind) {
        return switch (frameworkKind) {
            case MYBATIS_PLUS -> Optional.of(MYBATIS_PLUS);
            case MYBATIS_FLEX -> Optional.of(MYBATIS_FLEX);
            case TK_MAPPER -> Optional.empty();
        };
    }
}
