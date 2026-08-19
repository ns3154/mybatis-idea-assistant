package io.github.ns3154.mybatisassistant.methodsql;

import org.jetbrains.annotations.NotNull;

/**
 * 成功结果与失败诊断必须显式分支处理，禁止带错误继续生成。
 */
public sealed interface MyBatisMethodParseResult
        permits MyBatisMethodParseResult.Success, MyBatisMethodParseResult.Failure {
    record Success(@NotNull MyBatisMethodQuery query) implements MyBatisMethodParseResult {
    }

    record Failure(@NotNull MyBatisMethodDiagnostic diagnostic) implements MyBatisMethodParseResult {
    }
}
