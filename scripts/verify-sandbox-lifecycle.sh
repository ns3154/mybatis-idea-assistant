#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly CYCLE_COUNT="${1:-20}"
readonly REPORT_DIR="${PROJECT_ROOT}/build/reports/lifecycle"
readonly SANDBOX_LOG="${PROJECT_ROOT}/build/idea-sandbox/mybatis-idea-assistant/IU-2026.1.4/log/idea.log"
readonly SUMMARY_FILE="${REPORT_DIR}/sandbox-lifecycle.tsv"

if ! [[ "${CYCLE_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
    echo "循环次数必须是正整数：${CYCLE_COUNT}" >&2
    exit 2
fi

mkdir -p "${REPORT_DIR}"
printf 'cycle\tstart_line\tshutdown_line\tplugin_loaded\tplugin_error_count\texit_code\n' > "${SUMMARY_FILE}"

cd "${PROJECT_ROOT}"
./gradlew prepareSandbox >/dev/null
touch "${SANDBOX_LOG}"

wait_for_pattern() {
    local pattern="$1"
    local start_line="$2"
    local timeout_seconds="$3"
    local elapsed=0

    while (( elapsed < timeout_seconds )); do
        if awk -v start="${start_line}" -v pattern="${pattern}" \
            'NR >= start && index($0, pattern) > 0 { found = 1 } END { exit !found }' \
            "${SANDBOX_LOG}"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

for ((cycle = 1; cycle <= CYCLE_COUNT; cycle++)); do
    cycle_name="$(printf '%02d' "${cycle}")"
    cycle_output="${REPORT_DIR}/cycle-${cycle_name}.log"
    # 每轮日志已单独归档；重置共享沙箱日志，避免 IDEA 日志滚动后累计行号失效。
    : > "${SANDBOX_LOG}"
    start_line=$(( $(wc -l < "${SANDBOX_LOG}") + 1 ))

    ./gradlew runIde >"${cycle_output}" 2>&1 &
    run_pid=$!

    if ! wait_for_pattern "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
        kill "${run_pid}" 2>/dev/null || true
        wait "${run_pid}" 2>/dev/null || true
        echo "第 ${cycle} 次启动未在 90 秒内加载插件，详见 ${cycle_output}" >&2
        exit 1
    fi

    ./gradlew runIde --args=exit >>"${cycle_output}" 2>&1

    set +e
    wait "${run_pid}"
    run_exit_code=$?
    set -e

    if ! wait_for_pattern "IDE SHUTDOWN" "${start_line}" 30; then
        echo "第 ${cycle} 次关闭未记录 IDE SHUTDOWN，详见 ${cycle_output}" >&2
        exit 1
    fi

    shutdown_line="$(tail -n "+${start_line}" "${SANDBOX_LOG}" \
        | grep -n -- "IDE SHUTDOWN" \
        | tail -n 1 \
        | cut -d: -f1)"
    shutdown_line=$((start_line + shutdown_line - 1))
    cycle_slice="${REPORT_DIR}/cycle-${cycle_name}-idea.log"
    sed -n "${start_line},${shutdown_line}p" "${SANDBOX_LOG}" > "${cycle_slice}"

    plugin_loaded=0
    if grep -q -- "Loaded custom plugins: MyBatis Assistant" "${cycle_slice}"; then
        plugin_loaded=1
    fi
    plugin_error_count="$(grep -E -c \
        'ERROR .*MyBatis Assistant|PluginException.*io\.github\.ns3154|NoClassDefFoundError.*mybatisassistant|ClassNotFoundException.*mybatisassistant|^[[:space:]]+at io\.github\.ns3154\.mybatisassistant' \
        "${cycle_slice}" || true)"

    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${cycle}" \
        "${start_line}" \
        "${shutdown_line}" \
        "${plugin_loaded}" \
        "${plugin_error_count}" \
        "${run_exit_code}" >> "${SUMMARY_FILE}"

    if (( run_exit_code != 0 || plugin_loaded != 1 || plugin_error_count != 0 )); then
        echo "第 ${cycle} 次生命周期失败，详见 ${cycle_slice}" >&2
        exit 1
    fi
done

echo "沙箱生命周期验证通过：${CYCLE_COUNT}/${CYCLE_COUNT}，报告：${SUMMARY_FILE}"
