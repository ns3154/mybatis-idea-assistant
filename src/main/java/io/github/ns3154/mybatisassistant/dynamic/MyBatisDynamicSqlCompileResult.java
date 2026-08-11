package io.github.ns3154.mybatisassistant.dynamic;

import org.jetbrains.annotations.NotNull;

/**
 * 动态 SQL 编译的生命周期安全结果。
 */
public sealed interface MyBatisDynamicSqlCompileResult {
    record Compiled(@NotNull MyBatisDynamicSqlProgram program)
            implements MyBatisDynamicSqlCompileResult {
    }

    record IndexNotReady() implements MyBatisDynamicSqlCompileResult {
    }

    record SourceInvalid() implements MyBatisDynamicSqlCompileResult {
    }

    record UnsupportedSource() implements MyBatisDynamicSqlCompileResult {
    }
}
