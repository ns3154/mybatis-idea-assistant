package io.github.ns3154.mybatisassistant.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

/**
 * 跨受支持 IDE 版本执行稳定的同步读动作。
 */
public final class MyBatisReadActionSupport {
    private MyBatisReadActionSupport() {
    }

    public static <T> T compute(@NotNull Computable<T> computation) {
        return ApplicationManager.getApplication().runReadAction(computation);
    }
}
