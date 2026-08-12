package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

import javax.lang.model.SourceVersion;

/**
 * 生成 Java 源码前验证只能写入无泛型、无数组的安全全限定类型名。
 */
final class MyBatisJavaTypeValidator {
    private MyBatisJavaTypeValidator() {
    }

    static void requireQualifiedName(@NotNull String value, @NotNull String role) {
        if (!value.contains(".") || !SourceVersion.isName(value)) {
            throw new IllegalArgumentException(MyBatisMethodSqlMessages.message(
                    "methodsql.error.java.type.invalid", role, value));
        }
    }
}
