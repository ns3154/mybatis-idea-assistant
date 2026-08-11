#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SANDBOX_ROOT="${PROJECT_ROOT}/build/idea-sandbox/mybatis-idea-assistant/IU-2026.1.4"
readonly SANDBOX_LOG="${SANDBOX_ROOT}/log/idea.log"
readonly DISABLED_PLUGINS_FILE="${SANDBOX_ROOT}/config/disabled_plugins.txt"
readonly REPORT_DIR="${PROJECT_ROOT}/build/reports/optional-dependency-isolation"
readonly SUMMARY_FILE="${REPORT_DIR}/optional-dependency-isolation.tsv"
readonly SECONDARY_SCREEN_GATE_DIR="${MYBATIS_ASSISTANT_SECONDARY_SCREEN_GATE_DIR:-}"

readonly CASE_NAMES=(
    "kotlin"
    "spring"
    "yaml"
    "database"
    "all"
)
readonly CASE_PLUGIN_IDS=(
    "org.jetbrains.kotlin"
    "com.intellij.spring"
    "org.jetbrains.plugins.yaml"
    "com.intellij.database"
    "org.jetbrains.kotlin,com.intellij.spring,org.jetbrains.plugins.yaml,com.intellij.database"
)

mkdir -p "${REPORT_DIR}"

if [[ -n "${SECONDARY_SCREEN_GATE_DIR}" ]]; then
    case "${SECONDARY_SCREEN_GATE_DIR}" in
        "${PROJECT_ROOT}"/build/*) ;;
        *)
            echo "副屏确认目录必须位于项目 build 目录：${SECONDARY_SCREEN_GATE_DIR}" >&2
            exit 2
            ;;
    esac
    mkdir -p "${SECONDARY_SCREEN_GATE_DIR}"
fi

cd "${PROJECT_ROOT}"
./gradlew prepareSandbox >/dev/null
touch "${SANDBOX_LOG}" "${DISABLED_PLUGINS_FILE}"

readonly ORIGINAL_DISABLED_PLUGINS="$(mktemp)"
cp "${DISABLED_PLUGINS_FILE}" "${ORIGINAL_DISABLED_PLUGINS}"

restore_disabled_plugins() {
    cp "${ORIGINAL_DISABLED_PLUGINS}" "${DISABLED_PLUGINS_FILE}"
    rm -f "${ORIGINAL_DISABLED_PLUGINS}"
}
trap restore_disabled_plugins EXIT

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

printf 'case\tdisabled_plugins\tplugin_loaded\tplugin_error_count\texit_code\n' > "${SUMMARY_FILE}"

for index in "${!CASE_NAMES[@]}"; do
    case_name="${CASE_NAMES[${index}]}"
    plugin_ids="${CASE_PLUGIN_IDS[${index}]}"
    case_output="${REPORT_DIR}/${case_name}.log"
    case_idea_log="${REPORT_DIR}/${case_name}-idea.log"

    tr ',' '\n' <<< "${plugin_ids}" > "${DISABLED_PLUGINS_FILE}"
    # 每个场景日志已单独归档；重置共享沙箱日志，避免 IDEA 日志滚动后累计行号失效。
    : > "${SANDBOX_LOG}"
    start_line=$(( $(wc -l < "${SANDBOX_LOG}") + 1 ))

    ./gradlew runIde >"${case_output}" 2>&1 &
    run_pid=$!

    if ! wait_for_pattern "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
        kill "${run_pid}" 2>/dev/null || true
        wait "${run_pid}" 2>/dev/null || true
        echo "可选依赖隔离场景 ${case_name} 未在 90 秒内加载插件，详见 ${case_output}" >&2
        exit 1
    fi

    if [[ -n "${SECONDARY_SCREEN_GATE_DIR}" ]]; then
        ready_file="${SECONDARY_SCREEN_GATE_DIR}/${case_name}.ready"
        moved_file="${SECONDARY_SCREEN_GATE_DIR}/${case_name}.moved"
        rm -f "${ready_file}" "${moved_file}"
        : > "${ready_file}"
        elapsed=0
        while [[ ! -f "${moved_file}" && ${elapsed} -lt 90 ]]; do
            sleep 1
            elapsed=$((elapsed + 1))
        done
        if [[ ! -f "${moved_file}" ]]; then
            kill "${run_pid}" 2>/dev/null || true
            wait "${run_pid}" 2>/dev/null || true
            echo "可选依赖隔离场景 ${case_name} 未在 90 秒内完成副屏确认" >&2
            exit 1
        fi
    fi

    ./gradlew runIde --args=exit >>"${case_output}" 2>&1

    set +e
    wait "${run_pid}"
    run_exit_code=$?
    set -e

    if ! wait_for_pattern "IDE SHUTDOWN" "${start_line}" 30; then
        echo "可选依赖隔离场景 ${case_name} 未记录 IDE SHUTDOWN，详见 ${case_output}" >&2
        exit 1
    fi

    shutdown_line="$(tail -n "+${start_line}" "${SANDBOX_LOG}" \
        | grep -n -- "IDE SHUTDOWN" \
        | tail -n 1 \
        | cut -d: -f1)"
    shutdown_line=$((start_line + shutdown_line - 1))
    sed -n "${start_line},${shutdown_line}p" "${SANDBOX_LOG}" > "${case_idea_log}"

    plugin_loaded=0
    if grep -q -- "Loaded custom plugins: MyBatis Assistant" "${case_idea_log}"; then
        plugin_loaded=1
    fi
    plugin_error_count="$(grep -E -c \
        'ERROR .*MyBatis Assistant|PluginException.*io\.github\.ns3154|NoClassDefFoundError.*mybatisassistant|ClassNotFoundException.*mybatisassistant|^[[:space:]]+at io\.github\.ns3154\.mybatisassistant' \
        "${case_idea_log}" || true)"

    printf '%s\t%s\t%s\t%s\t%s\n' \
        "${case_name}" \
        "${plugin_ids}" \
        "${plugin_loaded}" \
        "${plugin_error_count}" \
        "${run_exit_code}" >> "${SUMMARY_FILE}"

    if (( run_exit_code != 0 || plugin_loaded != 1 || plugin_error_count != 0 )); then
        echo "可选依赖隔离场景 ${case_name} 失败，详见 ${case_idea_log}" >&2
        exit 1
    fi
done

echo "可选依赖隔离验证通过：${#CASE_NAMES[@]}/${#CASE_NAMES[@]}，报告：${SUMMARY_FILE}"
