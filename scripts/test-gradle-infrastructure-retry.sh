#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TEMP_ROOT="$(mktemp -d)"

cleanup() {
    rm -rf -- "${TEMP_ROOT}"
}
trap cleanup EXIT

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
    "只有限流文案但没有 HTTP 429" \
    "Too Many Requests"
assert_not_retryable \
    "覆盖率失败" \
    "Rule violated for bundle mybatis-idea-assistant: lines covered ratio is 0.69"
assert_not_retryable \
    "Plugin Verifier 不兼容" \
    "Plugin is not compatible with IU-262"

mkdir -p "${TEMP_ROOT}/layoutIndex"
printf '%s\n' "不完整布局索引" > "${TEMP_ROOT}/layoutIndex/index.json"
quarantine_layout_index \
    "${TEMP_ROOT}/layoutIndex" \
    "${TEMP_ROOT}/failed-layout-index-attempt-1"
test ! -e "${TEMP_ROOT}/layoutIndex"
test -f "${TEMP_ROOT}/failed-layout-index-attempt-1/index.json"

retry_fixture="${TEMP_ROOT}/retry-fixture"
mkdir -p "${retry_fixture}/scripts"
cp "${PROJECT_ROOT}/scripts/run-gradle-with-infrastructure-retry.sh" \
    "${retry_fixture}/scripts/run-gradle-with-infrastructure-retry.sh"
cat > "${retry_fixture}/gradlew" <<'FIXTURE'
#!/usr/bin/env bash

set -euo pipefail

attempt_file=".fixture-attempt"
attempt=1
if [[ -f "${attempt_file}" ]]; then
    attempt=$(( $(<"${attempt_file}") + 1 ))
fi
printf '%s\n' "${attempt}" > "${attempt_file}"

if (( attempt == 1 )); then
    mkdir -p .intellijPlatform/layoutIndex
    printf '%s\n' "不完整布局索引" > .intellijPlatform/layoutIndex/index.json
    printf '%s\n' \
        "java.nio.file.ClosedFileSystemException" \
        "Could not find bundled plugin with ID: 'com.intellij.database'"
    exit 1
fi

arguments=" $* "
if [[ "${arguments}" != *" --no-daemon "* \
    || "${arguments}" != *" --no-configuration-cache "* ]]; then
    echo "第二次尝试没有隔离 Gradle 进程与 configuration cache" >&2
    exit 41
fi
if [[ -e .intellijPlatform/layoutIndex ]]; then
    echo "第二次尝试仍看到不完整布局索引" >&2
    exit 42
fi

echo "BUILD SUCCESSFUL"
FIXTURE
chmod +x "${retry_fixture}/gradlew" \
    "${retry_fixture}/scripts/run-gradle-with-infrastructure-retry.sh"

(
    cd "${retry_fixture}"
    GRADLE_INFRASTRUCTURE_RETRY_DELAY_SECONDS=0 \
        ./scripts/run-gradle-with-infrastructure-retry.sh help >/dev/null
)
test "$(<"${retry_fixture}/.fixture-attempt")" = "2"
retry_report="$(find "${retry_fixture}/build/reports/gradle-infrastructure-retry" \
    -mindepth 1 -maxdepth 1 -type d -print -quit)"
test -n "${retry_report}"
test -f "${retry_report}/attempt-1.log"
test -f "${retry_report}/attempt-2.log"
test ! -f "${retry_report}/attempt-3.log"
test -f "${retry_report}/failed-layout-index-attempt-1/index.json"

echo "Gradle 基础设施重试白名单测试通过"
