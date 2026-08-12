#!/usr/bin/env bash

set -euo pipefail

is_retryable_failure() {
    local log_file="$1"

    awk '
        index($0, "ClosedFileSystemException") > 0 { closed_file_system = 1 }
        index($0, "Could not find bundled plugin with ID") > 0 { bundled_plugin_missing = 1 }
        /status code 429|429 .*Too Many Requests|Too Many Requests/ { rate_limited = 1 }
        END {
            # IJPG 2.18.1 / plugin-structure 3.330 的已知冷布局索引竞态：
            # https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2192
            # 另一条白名单只接受明确的 HTTP 429。
            exit !((closed_file_system && bundled_plugin_missing) || rate_limited)
        }
    ' "${log_file}"
}

run_gradle_with_infrastructure_retry() {
    local project_root report_root report_dir invocation_id max_attempts
    local attempt log_file gradle_exit_code delay_seconds
    project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
    report_root="${project_root}/build/reports/gradle-infrastructure-retry"
    invocation_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
    report_dir="${report_root}/${invocation_id}"
    max_attempts="${GRADLE_INFRASTRUCTURE_MAX_ATTEMPTS:-3}"

    if (( $# == 0 )); then
        echo "至少需要提供一个 Gradle 任务或参数" >&2
        return 2
    fi
    if ! [[ "${max_attempts}" =~ ^[1-3]$ ]]; then
        echo "GRADLE_INFRASTRUCTURE_MAX_ATTEMPTS 必须是 1～3 的整数" >&2
        return 2
    fi

    mkdir -p "${report_dir}"
    cd "${project_root}"

    attempt=1
    while (( attempt <= max_attempts )); do
        log_file="${report_dir}/attempt-${attempt}.log"
        printf 'Gradle 基础设施尝试 %d/%d：./gradlew' "${attempt}" "${max_attempts}"
        printf ' %q' "$@"
        printf '\n'

        set +e
        ./gradlew "$@" 2>&1 | tee "${log_file}"
        gradle_exit_code=${PIPESTATUS[0]}
        set -e

        if (( gradle_exit_code == 0 )); then
            return 0
        fi
        if (( attempt == max_attempts )) || ! is_retryable_failure "${log_file}"; then
            echo "Gradle 失败不属于可重试基础设施故障，或已达到重试上限" >&2
            return "${gradle_exit_code}"
        fi

        delay_seconds=$((attempt * 15))
        echo "检测到已知基础设施故障，${delay_seconds} 秒后进行有界重试" >&2
        sleep "${delay_seconds}"
        attempt=$((attempt + 1))
    done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    run_gradle_with_infrastructure_retry "$@"
fi
