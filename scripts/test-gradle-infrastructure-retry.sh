#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 只加载分类函数；被 source 时不会执行 Gradle。
source "${PROJECT_ROOT}/scripts/run-gradle-with-infrastructure-retry.sh"

assert_retryable() {
    local description="$1"
    shift
    if ! is_retryable_failure <(printf '%s\n' "$@"); then
        echo "应允许重试：${description}" >&2
        exit 1
    fi
}

assert_not_retryable() {
    local description="$1"
    shift
    if is_retryable_failure <(printf '%s\n' "$@"); then
        echo "不应允许重试：${description}" >&2
        exit 1
    fi
}

assert_retryable \
    "IJPG 冷布局索引竞态" \
    "java.nio.file.ClosedFileSystemException" \
    "Could not find bundled plugin with ID: 'com.intellij.database'"
assert_retryable \
    "Maven Central HTTP 429" \
    "Received status code 429 from server: Too Many Requests"

assert_not_retryable \
    "只有 ClosedFileSystemException，缺少捆绑插件误报" \
    "java.nio.file.ClosedFileSystemException"
assert_not_retryable \
    "普通捆绑插件配置错误" \
    "Could not find bundled plugin with ID: 'com.intellij.database'"
assert_not_retryable \
    "测试失败" \
    "There were failing tests"
assert_not_retryable \
    "覆盖率失败" \
    "Rule violated for bundle mybatis-idea-assistant: lines covered ratio is 0.69"
assert_not_retryable \
    "Plugin Verifier 不兼容" \
    "Plugin is not compatible with IU-262"

echo "Gradle 基础设施重试白名单测试通过"
