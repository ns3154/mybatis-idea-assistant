package io.github.ns3154.mybatisassistant.reference;

import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * 向数据库生成链路暴露 ResultMap 根类型上可证明唯一的可写属性。
 */
public final class MyBatisResultPropertySupport {
    private MyBatisResultPropertySupport() {
    }

    public static @NotNull Optional<List<String>> rootWritableProperties(
            @NotNull XmlTag resultMap) {
        return MyBatisResultPropertyPathResolver.rootWritableProperties(resultMap);
    }
}
