#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly CYCLE_COUNT="${1:-20}"
readonly PROJECT_PATH_INPUT="${2:-}"
readonly REBUILD_INDEXES="${3:-false}"
readonly PLATFORM_VERSION_OVERRIDE="${4:-}"
readonly REPORT_DIR="${PROJECT_ROOT}/build/reports/lifecycle"
readonly DEFAULT_PLATFORM_VERSION="$(sed -n 's/^platformVersion=//p' "${PROJECT_ROOT}/gradle.properties")"
readonly PLATFORM_VERSION="${PLATFORM_VERSION_OVERRIDE:-${DEFAULT_PLATFORM_VERSION}}"
readonly SANDBOX_ROOT="${PROJECT_ROOT}/build/idea-sandbox/mybatis-idea-assistant/IU-${PLATFORM_VERSION}"
readonly SANDBOX_LOG="${SANDBOX_ROOT}/log/idea.log"
readonly SANDBOX_INDEX_DIR="${SANDBOX_ROOT}/system/index"
readonly SUMMARY_FILE="${REPORT_DIR}/sandbox-lifecycle.tsv"

if ! [[ "${CYCLE_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
    echo "循环次数必须是正整数：${CYCLE_COUNT}" >&2
    exit 2
fi
if [[ -z "${PLATFORM_VERSION}" ]]; then
    echo "gradle.properties 缺少 platformVersion" >&2
    exit 2
fi
if [[ ! "${PLATFORM_VERSION}" =~ ^[0-9]{4}\.[0-9]+([.][0-9]+)*$ ]]; then
    echo "IDE 版本格式不正确：${PLATFORM_VERSION}" >&2
    exit 2
fi
if [[ "${REBUILD_INDEXES}" != "true" && "${REBUILD_INDEXES}" != "false" ]]; then
    echo "索引重建参数只能是 true 或 false：${REBUILD_INDEXES}" >&2
    exit 2
fi

PROJECT_PATH=""
PROJECT_NAME=""
if [[ -n "${PROJECT_PATH_INPUT}" ]]; then
    if [[ "${PROJECT_PATH_INPUT}" = /* ]]; then
        candidate_project_path="${PROJECT_PATH_INPUT}"
    else
        candidate_project_path="${PROJECT_ROOT}/${PROJECT_PATH_INPUT}"
    fi
    if [[ ! -d "${candidate_project_path}" ]]; then
        echo "生命周期项目目录不存在：${candidate_project_path}" >&2
        exit 2
    fi
    PROJECT_PATH="$(cd "${candidate_project_path}" && pwd)"
    case "${PROJECT_PATH}" in
        "${PROJECT_ROOT}"/*) ;;
        *)
            echo "生命周期项目必须位于当前仓库内：${PROJECT_PATH}" >&2
            exit 2
            ;;
    esac
    PROJECT_NAME="$(basename "${PROJECT_PATH}")"
fi

case "${SANDBOX_INDEX_DIR}" in
    "${PROJECT_ROOT}"/build/idea-sandbox/*/system/index) ;;
    *)
        echo "拒绝使用不安全的沙箱索引目录：${SANDBOX_INDEX_DIR}" >&2
        exit 2
        ;;
esac

mkdir -p "${REPORT_DIR}"
printf 'cycle\tplatform_version\tstart_line\tshutdown_line\tplugin_loaded\tproject_opened\tindex_scan_completed\tindex_rebuilt\tproject_disposed\tplugin_error_count\texit_code\n' > "${SUMMARY_FILE}"

cd "${PROJECT_ROOT}"
readonly GRADLE_PLATFORM_ARGUMENT="-PplatformVersion=${PLATFORM_VERSION}"
readonly GRADLE_LOCK_ARGUMENT="-PdependencyLockFile=gradle/lifecycle-${PLATFORM_VERSION}.lockfile"
./gradlew "${GRADLE_PLATFORM_ARGUMENT}" "${GRADLE_LOCK_ARGUMENT}" \
    prepareSandbox >/dev/null
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

    if [[ "${REBUILD_INDEXES}" == "true" ]]; then
        # 仅删除当前仓库 build 下的沙箱索引；每轮都要求真实项目重新完成索引扫描。
        rm -rf -- "${SANDBOX_INDEX_DIR}"
    fi

    run_ide_command=(
        ./gradlew
        "${GRADLE_PLATFORM_ARGUMENT}"
        "${GRADLE_LOCK_ARGUMENT}"
        runIde
    )
    if [[ -n "${PROJECT_PATH}" ]]; then
        run_ide_command+=("--args=${PROJECT_PATH}")
    fi
    "${run_ide_command[@]}" >"${cycle_output}" 2>&1 &
    run_pid=$!

    if ! wait_for_pattern "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
        kill "${run_pid}" 2>/dev/null || true
        wait "${run_pid}" 2>/dev/null || true
        echo "第 ${cycle} 次启动未在 90 秒内加载插件，详见 ${cycle_output}" >&2
        exit 1
    fi

    if [[ -n "${PROJECT_PATH}" ]]; then
        if ! wait_for_pattern "Project ${PROJECT_NAME} was added to the list of open projects" \
            "${start_line}" 120; then
            kill "${run_pid}" 2>/dev/null || true
            wait "${run_pid}" 2>/dev/null || true
            echo "第 ${cycle} 次启动未在 120 秒内打开项目 ${PROJECT_NAME}，详见 ${cycle_output}" >&2
            exit 1
        fi
        if ! wait_for_pattern "Scanning completed for [${PROJECT_NAME}]" "${start_line}" 180; then
            kill "${run_pid}" 2>/dev/null || true
            wait "${run_pid}" 2>/dev/null || true
            echo "第 ${cycle} 次启动未在 180 秒内完成项目索引扫描，详见 ${cycle_output}" >&2
            exit 1
        fi
    fi

    ./gradlew "${GRADLE_PLATFORM_ARGUMENT}" "${GRADLE_LOCK_ARGUMENT}" \
        runIde --args=exit \
        >>"${cycle_output}" 2>&1

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
    project_opened=1
    index_scan_completed=1
    index_rebuilt=1
    project_disposed=1
    if [[ -n "${PROJECT_PATH}" ]]; then
        if ! grep -Fq -- \
            "Project ${PROJECT_NAME} was added to the list of open projects" "${cycle_slice}"; then
            project_opened=0
        fi
        if ! grep -Fq -- "Scanning completed for [${PROJECT_NAME}]" "${cycle_slice}"; then
            index_scan_completed=0
        fi
        if [[ "${REBUILD_INDEXES}" == "true" ]] && ! grep -Fq -- \
            "Full scanning on startup will NOT be skipped for project [${PROJECT_NAME}]" \
            "${cycle_slice}"; then
            index_rebuilt=0
        fi
        if ! grep -Fq -- \
            "Project ${PROJECT_NAME} is removed from the list of initializing and open projects. Project was disposed." \
            "${cycle_slice}"; then
            project_disposed=0
        fi
    fi
    plugin_error_count="$(grep -E -c \
        'ERROR .*MyBatis Assistant|PluginException.*io\.github\.ns3154|NoClassDefFoundError.*mybatisassistant|ClassNotFoundException.*mybatisassistant|^[[:space:]]+at io\.github\.ns3154\.mybatisassistant' \
        "${cycle_slice}" || true)"

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "${cycle}" \
        "${PLATFORM_VERSION}" \
        "${start_line}" \
        "${shutdown_line}" \
        "${plugin_loaded}" \
        "${project_opened}" \
        "${index_scan_completed}" \
        "${index_rebuilt}" \
        "${project_disposed}" \
        "${plugin_error_count}" \
        "${run_exit_code}" >> "${SUMMARY_FILE}"

    if (( run_exit_code != 0 \
        || plugin_loaded != 1 \
        || project_opened != 1 \
        || index_scan_completed != 1 \
        || index_rebuilt != 1 \
        || project_disposed != 1 \
        || plugin_error_count != 0 )); then
        echo "第 ${cycle} 次生命周期失败，详见 ${cycle_slice}" >&2
        exit 1
    fi
done

echo "沙箱生命周期验证通过：${CYCLE_COUNT}/${CYCLE_COUNT}，报告：${SUMMARY_FILE}"
