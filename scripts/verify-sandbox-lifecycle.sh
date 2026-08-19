#!/usr/bin/env bash

set -euo pipefail

# 本文件既是生命周期入口，也是可选依赖隔离脚本复用的只读校验函数库。
# 直接执行时才进入 main，source 时不会启动 IDEA 或修改沙箱。
readonly MYBATIS_ASSISTANT_LIFECYCLE_PROJECT_ROOT="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
)"
readonly MYBATIS_ASSISTANT_IDE_NOISE_ALLOWLIST_VERSION="2026-08-19.v3"
readonly MYBATIS_ASSISTANT_EXPECTED_INSPECTION="MyBatisUnusedStatement"
readonly MYBATIS_ASSISTANT_EXPECTED_NAMESPACE="io.github.mybatisideaassistant.lifecycle.UserMapper"
readonly MYBATIS_ASSISTANT_EXPECTED_STATEMENT="findSummary"
readonly MYBATIS_ASSISTANT_EXPECTED_SOURCE="src/main/resources/mappers/UserMapper.xml"
readonly MYBATIS_ASSISTANT_MAX_SANDBOX_GROWTH_BYTES="${MYBATIS_ASSISTANT_MAX_SANDBOX_GROWTH_BYTES:-268435456}"
readonly MYBATIS_ASSISTANT_MAX_SANDBOX_FILE_GROWTH="${MYBATIS_ASSISTANT_MAX_SANDBOX_FILE_GROWTH:-10000}"
readonly MYBATIS_ASSISTANT_MAX_IDE_PROCESS_GROWTH="${MYBATIS_ASSISTANT_MAX_IDE_PROCESS_GROWTH:-8}"

# JetBrains 2025.2/2026.1 inspect 已知会把这组缺失的内置检查描述记录为 SEVERE。
# 这里只接受逐项列出的工具名；出现新增工具、不同 logger 或不同正文时立即失败。
readonly MYBATIS_ASSISTANT_DESCRIPTION_NOISE_TOOLS="|MongoJSResolveInspection|MongoJSDeprecationInspection|MsBuiltinInspection|MsOrderByInspection|SpringBootAdditionalConfig|OraOverloadInspection|OraUnmatchedForwardDeclarationInspection|OraMissingBodyInspection|DeclarativeUnresolvedReference|PgSelectFromProcedureInspection|MysqlLoadDataPathInspection|MysqlSpaceAfterFunctionNameInspection|MysqlParsingInspection|"

MYBATIS_ASSISTANT_ACTIVE_RUN_PID=""
MYBATIS_ASSISTANT_ACTIVE_RUN_STARTED_AT=""
MYBATIS_ASSISTANT_ACTIVE_RUN_COMMAND=""
MYBATIS_ASSISTANT_ACTIVE_IDE_PID=""
MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT=""
MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND=""
MYBATIS_ASSISTANT_ACTIVE_PROCESS_IDENTITIES=""
MYBATIS_ASSISTANT_SETTINGS_FILE=""
MYBATIS_ASSISTANT_SETTINGS_BACKUP=""
MYBATIS_ASSISTANT_SETTINGS_EXISTED=0
MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT=0
MYBATIS_ASSISTANT_IDE_PID=""

mybatis_assistant_wait_for_pattern() {
    local log_file="$1"
    local pattern="$2"
    local start_line="$3"
    local timeout_seconds="$4"
    local elapsed=0

    while (( elapsed < timeout_seconds )); do
        if awk -v start="${start_line}" -v pattern="${pattern}" \
            'NR >= start && index($0, pattern) > 0 { found = 1 } END { exit !found }' \
            "${log_file}"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

mybatis_assistant_process_is_running() {
    local process_id="$1"
    local state
    [[ "${process_id}" =~ ^[1-9][0-9]*$ ]] || return 1
    state="$(ps -p "${process_id}" -o stat= 2>/dev/null | awk 'NR == 1 { print $1 }')"
    [[ -n "${state}" && "${state}" != Z* ]]
}

mybatis_assistant_process_started_at() {
    local process_id="$1"
    LC_ALL=C ps -p "${process_id}" -o lstart= 2>/dev/null \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

mybatis_assistant_process_command() {
    local process_id="$1"
    ps -p "${process_id}" -o command= 2>/dev/null \
        | tr '\t\r\n' '   ' \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

mybatis_assistant_process_identity_matches() {
    local process_id="$1"
    local expected_started_at="$2"
    local expected_command_pattern="$3"
    local current_started_at
    local current_command

    [[ "${process_id}" =~ ^[1-9][0-9]*$ && "${process_id}" -gt 1 ]] || return 1
    [[ -n "${expected_started_at}" && -n "${expected_command_pattern}" ]] || return 1
    current_started_at="$(mybatis_assistant_process_started_at "${process_id}" || true)"
    current_command="$(mybatis_assistant_process_command "${process_id}" || true)"
    [[ "${current_started_at}" == "${expected_started_at}" \
        && "${current_command}" == *"${expected_command_pattern}"* ]]
}

mybatis_assistant_process_exact_identity_matches() {
    local process_id="$1"
    local expected_started_at="$2"
    local expected_command="$3"
    local current_started_at
    local current_command

    [[ "${process_id}" =~ ^[1-9][0-9]*$ && "${process_id}" -gt 1 ]] || return 1
    [[ -n "${expected_started_at}" && -n "${expected_command}" ]] || return 1
    mybatis_assistant_process_is_running "${process_id}" || return 1
    current_started_at="$(mybatis_assistant_process_started_at "${process_id}" || true)"
    current_command="$(mybatis_assistant_process_command "${process_id}" || true)"
    [[ "${current_started_at}" == "${expected_started_at}" \
        && "${current_command}" == "${expected_command}" ]]
}

mybatis_assistant_write_process_identity() {
    local process_id="$1"
    local command_pattern="$2"
    local identity_file="$3"
    local started_at
    local command_line

    started_at="$(mybatis_assistant_process_started_at "${process_id}" || true)"
    [[ -n "${started_at}" ]] || return 1
    mybatis_assistant_process_identity_matches \
        "${process_id}" "${started_at}" "${command_pattern}" || return 1
    command_line="$(mybatis_assistant_process_command "${process_id}" || true)"
    [[ -n "${command_line}" ]] || return 1
    printf '%s\t%s\t%s\n' \
        "${process_id}" "${started_at}" "${command_line}" > "${identity_file}"
}

mybatis_assistant_wait_for_process_identity() {
    local process_id="$1"
    local command_pattern="$2"
    local timeout_seconds="$3"
    local identity_file="${4:-}"
    local elapsed=0
    local started_at
    local command_line

    while (( elapsed < timeout_seconds )); do
        started_at="$(mybatis_assistant_process_started_at "${process_id}" || true)"
        command_line="$(mybatis_assistant_process_command "${process_id}" || true)"
        if [[ -n "${identity_file}" && -n "${started_at}" \
            && -n "${command_line}" ]]; then
            printf '%s\t%s\t%s\n' \
                "${process_id}" "${started_at}" "${command_line}" >> "${identity_file}"
            LC_ALL=C sort -u "${identity_file}" -o "${identity_file}"
        fi
        if mybatis_assistant_process_identity_matches \
            "${process_id}" "${started_at}" "${command_pattern}"; then
            printf '%s\n' "${started_at}"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

mybatis_assistant_ide_command_pattern() {
    local process_id="$1"
    local command_line
    command_line="$(mybatis_assistant_process_command "${process_id}" || true)"
    if [[ "${command_line}" == *"com.intellij.idea.Main"* ]]; then
        printf '%s\n' 'com.intellij.idea.Main'
    elif [[ "${command_line}" == *"idea.platform.prefix=Idea"* ]]; then
        printf '%s\n' 'idea.platform.prefix=Idea'
    else
        return 1
    fi
}

mybatis_assistant_process_tree_pids() {
    local root_pid="$1"
    ps -eo pid=,ppid= | awk -v root="${root_pid}" '
        {
            pid[NR] = $1
            parent[NR] = $2
        }
        END {
            selected[root] = 1
            do {
                changed = 0
                for (row = 1; row <= NR; row++) {
                    if (selected[parent[row]] && !selected[pid[row]]) {
                        selected[pid[row]] = 1
                        changed = 1
                    }
                }
            } while (changed)
            for (candidate in selected) {
                if (selected[candidate]) {
                    print candidate
                }
            }
        }
    ' | LC_ALL=C sort -n
}

mybatis_assistant_process_is_in_tree() {
    local root_pid="$1"
    local candidate_pid="$2"
    mybatis_assistant_process_tree_pids "${root_pid}" \
        | awk -v candidate="${candidate_pid}" '$1 == candidate { found = 1 } END { exit !found }'
}

mybatis_assistant_find_ide_pid() {
    local run_pid="$1"
    local timeout_seconds="$2"
    local elapsed=0
    local process_id
    local command_line

    while (( elapsed < timeout_seconds )); do
        while IFS= read -r process_id; do
            command_line="$(ps -p "${process_id}" -o command= 2>/dev/null || true)"
            if [[ "${command_line}" == *"com.intellij.idea.Main"* \
                || "${command_line}" == *"idea.platform.prefix=Idea"* ]]; then
                printf '%s\n' "${process_id}"
                return 0
            fi
        done < <(mybatis_assistant_process_tree_pids "${run_pid}")
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

mybatis_assistant_record_process_tree() {
    local root_pid="$1"
    local identity_file="$2"
    local process_id
    local started_at
    local command_line

    while IFS= read -r process_id; do
        started_at="$(LC_ALL=C ps -p "${process_id}" -o lstart= 2>/dev/null \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' || true)"
        command_line="$(mybatis_assistant_process_command "${process_id}" || true)"
        if [[ -n "${started_at}" && -n "${command_line}" ]]; then
            printf '%s\t%s\t%s\n' \
                "${process_id}" "${started_at}" "${command_line}" >> "${identity_file}"
        fi
    done < <(mybatis_assistant_process_tree_pids "${root_pid}")
    LC_ALL=C sort -u "${identity_file}" -o "${identity_file}"
}

mybatis_assistant_write_live_recorded_processes() {
    local identity_file="$1"
    local output_file="$2"
    local process_id
    local expected_started_at
    local expected_command

    : > "${output_file}"
    while IFS=$'\t' read -r \
        process_id expected_started_at expected_command; do
        [[ -n "${process_id}" ]] || continue
        if mybatis_assistant_process_exact_identity_matches \
            "${process_id}" "${expected_started_at}" "${expected_command}"; then
            printf '%s\t%s\t%s\n' \
                "${process_id}" "${expected_started_at}" "${expected_command}" \
                >> "${output_file}"
        fi
    done < "${identity_file}"
}

mybatis_assistant_refresh_recorded_process_forest() {
    local identity_file="$1"
    local snapshot_file="${identity_file}.refresh.$$"
    local process_id
    local expected_started_at
    local expected_command

    [[ -f "${identity_file}" ]] || return 1
    cp "${identity_file}" "${snapshot_file}"
    while IFS=$'\t' read -r \
        process_id expected_started_at expected_command; do
        [[ -n "${process_id}" ]] || continue
        if mybatis_assistant_process_exact_identity_matches \
            "${process_id}" "${expected_started_at}" "${expected_command}"; then
            # Gradle wrapper 退出后，IDEA 仍可能启动新的分析器或清理子进程。
            # 每轮等待都扩展已知进程森林，不能只依赖 wrapper 存活期的快照。
            mybatis_assistant_record_process_tree \
                "${process_id}" "${identity_file}"
        fi
    done < "${snapshot_file}"
    rm -f -- "${snapshot_file}"
}

mybatis_assistant_wait_for_recorded_processes_exit() {
    local identity_file="$1"
    local live_file="$2"
    local timeout_seconds="$3"
    local elapsed=0
    local empty_polls=0

    while (( elapsed < timeout_seconds )); do
        mybatis_assistant_refresh_recorded_process_forest "${identity_file}" \
            || return 1
        mybatis_assistant_write_live_recorded_processes "${identity_file}" "${live_file}"
        if [[ ! -s "${live_file}" ]]; then
            empty_polls=$((empty_polls + 1))
            # 连续两轮都为空才冻结日志，覆盖父进程退出边界附近刚登记的后代。
            if (( empty_polls >= 2 )); then
                return 0
            fi
        else
            empty_polls=0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    mybatis_assistant_refresh_recorded_process_forest "${identity_file}" \
        || return 1
    mybatis_assistant_write_live_recorded_processes "${identity_file}" "${live_file}"
    return 1
}

mybatis_assistant_observe_until_run_exit() {
    local run_pid="$1"
    local ide_pid="$2"
    local identity_file="$3"
    local timeout_seconds="$4"
    local elapsed=0
    local process_count

    MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT=0
    while mybatis_assistant_process_is_running "${run_pid}"; do
        if mybatis_assistant_process_is_running "${ide_pid}"; then
            mybatis_assistant_record_process_tree "${ide_pid}" "${identity_file}"
            process_count="$(mybatis_assistant_process_tree_pids "${ide_pid}" | wc -l \
                | tr -d '[:space:]')"
            if (( process_count > MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT )); then
                MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT="${process_count}"
            fi
        fi
        if (( elapsed >= timeout_seconds )); then
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 0
}

mybatis_assistant_freeze_lifecycle_log_after_process_exit() {
    local sandbox_log="$1"
    local start_line="$2"
    local ide_pid="$3"
    local ide_started_at="$4"
    local ide_command="$5"
    local process_identity_file="$6"
    local live_process_file="$7"
    local frozen_log="$8"
    local evidence_file="$9"
    local timeout_seconds="${10}"
    local shutdown_line_before_exit
    local shutdown_line_after_exit
    local frozen_line_count

    mybatis_assistant_wait_for_pattern \
        "${sandbox_log}" 'IDE SHUTDOWN' "${start_line}" \
        "${timeout_seconds}" || return 1
    shutdown_line_before_exit="$(awk -v start="${start_line}" '
        BEGIN {
            marker = " INFO - #c.i.p.i.b.AppStarter - ------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------"
        }
        NR >= start && length($0) >= length(marker) \
            && substr($0, length($0) - length(marker) + 1) == marker {
            count++
            marker_line = NR
        }
        END {
            if (count != 1) exit 1
            print marker_line
        }
    ' "${sandbox_log}")" || return 1

    # shutdown marker 只是顺序证据，不能作为日志已经写完的证明。若 IDEA 仍在，
    # 再记录一次此刻的完整子树，然后等待所有精确身份退出。
    if mybatis_assistant_process_exact_identity_matches \
        "${ide_pid}" "${ide_started_at}" "${ide_command}"; then
        mybatis_assistant_record_process_tree \
            "${ide_pid}" "${process_identity_file}"
    fi
    mybatis_assistant_wait_for_recorded_processes_exit \
        "${process_identity_file}" "${live_process_file}" \
        "${timeout_seconds}" || return 1
    if mybatis_assistant_process_exact_identity_matches \
        "${ide_pid}" "${ide_started_at}" "${ide_command}"; then
        return 1
    fi

    # 精确进程全部退出后才冻结本轮完整日志；marker 后的任何晚写内容都会被
    # 后续异常分类与插件错误计数覆盖。
    sed -n "${start_line},\$p" "${sandbox_log}" > "${frozen_log}"
    shutdown_line_after_exit="$(awk -v start="${start_line}" '
        BEGIN {
            marker = " INFO - #c.i.p.i.b.AppStarter - ------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------"
        }
        NR >= start && length($0) >= length(marker) \
            && substr($0, length($0) - length(marker) + 1) == marker {
            count++
            marker_line = NR
        }
        END {
            if (count != 1) exit 1
            print marker_line
        }
    ' "${sandbox_log}")" || return 1
    [[ "${shutdown_line_after_exit}" == "${shutdown_line_before_exit}" ]] \
        || return 1
    frozen_line_count="$(wc -l < "${frozen_log}" | tr -d '[:space:]')"
    (( frozen_line_count >= shutdown_line_after_exit - start_line + 1 )) \
        || return 1

    printf 'contract_version\tshutdown_marker_line\trecorded_processes_exited\tide_process_exited\tfrozen_line_count\n' \
        > "${evidence_file}"
    printf '%s\t%s\t1\t1\t%s\n' \
        '2026-08-13.v1' "${shutdown_line_after_exit}" \
        "${frozen_line_count}" >> "${evidence_file}"
    printf '%s\n' "${shutdown_line_after_exit}"
}

mybatis_assistant_signal_verified_process() {
    local signal_name="$1"
    local process_id="$2"
    local expected_started_at="$3"
    local expected_command="$4"

    mybatis_assistant_process_exact_identity_matches \
        "${process_id}" "${expected_started_at}" "${expected_command}" || return 0
    [[ "${process_id}" != "$$" ]] || return 0
    kill "-${signal_name}" "${process_id}" 2>/dev/null || true
}

mybatis_assistant_signal_recorded_processes() {
    local signal_name="$1"
    local identity_file="$2"
    local process_id
    local expected_started_at
    local expected_command

    [[ -n "${identity_file}" && -f "${identity_file}" ]] || return 0
    while IFS=$'\t' read -r \
        process_id expected_started_at expected_command; do
        [[ -n "${process_id}" ]] || continue
        mybatis_assistant_signal_verified_process \
            "${signal_name}" "${process_id}" \
            "${expected_started_at}" "${expected_command}"
    done < <(LC_ALL=C sort -rn "${identity_file}")
}

mybatis_assistant_terminate_scoped_run() {
    local run_pid="${1:-}"
    local run_started_at="${2:-}"
    local run_command="${3:-}"
    local ide_pid="${4:-}"
    local ide_started_at="${5:-}"
    local ide_command="${6:-}"
    local process_identity_file="${7:-}"
    local elapsed

    # 只终止本轮记录的 PID，不使用 pkill；TERM 与 KILL 前都复核身份。
    if [[ -n "${process_identity_file}" ]] \
        && mybatis_assistant_process_exact_identity_matches \
        "${run_pid}" "${run_started_at}" "${run_command}"; then
        mybatis_assistant_record_process_tree "${run_pid}" "${process_identity_file}"
    fi
    if [[ -n "${process_identity_file}" ]] \
        && mybatis_assistant_process_exact_identity_matches \
        "${ide_pid}" "${ide_started_at}" "${ide_command}"; then
        mybatis_assistant_record_process_tree "${ide_pid}" "${process_identity_file}"
    fi
    mybatis_assistant_signal_recorded_processes TERM "${process_identity_file}"
    mybatis_assistant_signal_verified_process \
        TERM "${ide_pid}" "${ide_started_at}" "${ide_command}"
    mybatis_assistant_signal_verified_process \
        TERM "${run_pid}" "${run_started_at}" "${run_command}"

    for ((elapsed = 0; elapsed < 10; elapsed++)); do
        if [[ -n "${process_identity_file}" && -f "${process_identity_file}" ]]; then
            mybatis_assistant_write_live_recorded_processes \
                "${process_identity_file}" "${process_identity_file}.cleanup-live"
        fi
        if ! mybatis_assistant_process_exact_identity_matches \
                "${run_pid}" "${run_started_at}" "${run_command}" \
            && ! mybatis_assistant_process_exact_identity_matches \
                "${ide_pid}" "${ide_started_at}" "${ide_command}" \
            && { [[ -z "${process_identity_file}" \
                    || ! -s "${process_identity_file}.cleanup-live" ]]; }; then
            return 0
        fi
        sleep 1
    done
    mybatis_assistant_signal_recorded_processes KILL "${process_identity_file}"
    mybatis_assistant_signal_verified_process \
        KILL "${ide_pid}" "${ide_started_at}" "${ide_command}"
    mybatis_assistant_signal_verified_process \
        KILL "${run_pid}" "${run_started_at}" "${run_command}"
    # KILL 后再给内核一个短窗口回收本轮精确身份，避免函数提前返回。
    for ((elapsed = 0; elapsed < 5; elapsed++)); do
        if [[ -n "${process_identity_file}" && -f "${process_identity_file}" ]]; then
            mybatis_assistant_write_live_recorded_processes \
                "${process_identity_file}" "${process_identity_file}.cleanup-live"
        fi
        if ! mybatis_assistant_process_exact_identity_matches \
                "${run_pid}" "${run_started_at}" "${run_command}" \
            && ! mybatis_assistant_process_exact_identity_matches \
                "${ide_pid}" "${ide_started_at}" "${ide_command}" \
            && { [[ -z "${process_identity_file}" \
                    || ! -s "${process_identity_file}.cleanup-live" ]]; }; then
            return 0
        fi
        sleep 1
    done
}

mybatis_assistant_listening_pids_for_port() {
    local port="$1"
    lsof -nP -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | LC_ALL=C sort -u || true
}

mybatis_assistant_port_is_free() {
    local port="$1"
    [[ -z "$(mybatis_assistant_listening_pids_for_port "${port}")" ]]
}

mybatis_assistant_wait_for_owned_mcp_port() {
    local run_pid="$1"
    local port="$2"
    local timeout_seconds="$3"
    local elapsed=0
    local listening_pids
    local listening_pid
    local listening_count

    MYBATIS_ASSISTANT_IDE_PID=""
    while (( elapsed < timeout_seconds )); do
        listening_pids="$(mybatis_assistant_listening_pids_for_port "${port}")"
        if [[ -n "${listening_pids}" ]]; then
            listening_count="$(printf '%s\n' "${listening_pids}" | wc -l \
                | tr -d '[:space:]')"
            if (( listening_count != 1 )); then
                return 1
            fi
            listening_pid="${listening_pids}"
            if ! mybatis_assistant_process_is_in_tree "${run_pid}" "${listening_pid}"; then
                return 1
            fi
            MYBATIS_ASSISTANT_IDE_PID="${listening_pid}"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

mybatis_assistant_wait_for_port_release() {
    local port="$1"
    local timeout_seconds="$2"
    local elapsed=0

    while (( elapsed < timeout_seconds )); do
        if mybatis_assistant_port_is_free "${port}"; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

mybatis_assistant_verify_mcp_probe_files() {
    local status_code="$1"
    local headers_file="$2"
    local body_file="$3"
    [[ "${status_code}" == "401" ]] || return 1
    grep -Eiq '^Cache-Control:[[:space:]]*no-store[[:space:]]*$' "${headers_file}" \
        || return 1
    grep -Eiq '^Content-Type:[[:space:]]*application/json;[[:space:]]*charset=utf-8[[:space:]]*$' \
        "${headers_file}" || return 1
    grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff[[:space:]]*$' \
        "${headers_file}" || return 1
    [[ "$(tr -d '\r\n' < "${body_file}")" == '{"error":"unauthorized"}' ]]
}

mybatis_assistant_probe_mcp_endpoint() {
    local port="$1"
    local evidence_prefix="$2"
    local headers_file="${evidence_prefix}-headers.txt"
    local body_file="${evidence_prefix}-body.json"
    local contract_file="${evidence_prefix}-contract.tsv"
    local status_code

    status_code="$(curl --silent --show-error \
        --connect-timeout 5 --max-time 10 \
        --noproxy '*' \
        --dump-header "${headers_file}" \
        --output "${body_file}" \
        --write-out '%{http_code}' \
        "http://127.0.0.1:${port}/mcp")" || return 1
    mybatis_assistant_verify_mcp_probe_files \
        "${status_code}" "${headers_file}" "${body_file}" || return 1
    printf 'contract_version\tport\tunauthenticated_status\tloopback_owner_verified\tsecurity_headers_verified\n' \
        > "${contract_file}"
    printf '%s\t%s\t%s\t1\t1\n' \
        '2026-08-12.v1' "${port}" "${status_code}" >> "${contract_file}"
}

mybatis_assistant_write_project_manifest() {
    local repository_root="$1"
    local project_path="$2"
    local output_file="$3"
    local project_relative_path="${project_path#${repository_root}/}"
    local relative_file
    local absolute_file
    local file_mode
    local object_hash

    : > "${output_file}"
    while IFS= read -r -d '' relative_file; do
        absolute_file="${repository_root}/${relative_file}"
        if [[ -L "${absolute_file}" ]]; then
            file_mode="120000"
            object_hash="$(readlink "${absolute_file}" | git -C "${repository_root}" hash-object --stdin)"
        elif [[ -x "${absolute_file}" ]]; then
            file_mode="100755"
            object_hash="$(git -C "${repository_root}" hash-object --no-filters -- "${absolute_file}")"
        else
            file_mode="100644"
            object_hash="$(git -C "${repository_root}" hash-object --no-filters -- "${absolute_file}")"
        fi
        printf '%s\t%s\t%s\n' "${file_mode}" "${object_hash}" "${relative_file}" \
            >> "${output_file}"
    done < <(git -C "${repository_root}" ls-files -z \
        --cached --others --exclude-standard -- "${project_relative_path}")
}

mybatis_assistant_ignored_project_path_is_controlled() {
    local project_relative_path="$1"
    local repository_relative_file="$2"
    local project_local_file="${repository_relative_file#${project_relative_path}/}"

    [[ "${repository_relative_file}" != "${project_local_file}" ]] || return 1
    case "/${project_local_file}" in
        */.idea/*|*/target/*|*/build/*|*/.gradle/*) return 0 ;;
        *) return 1 ;;
    esac
}

mybatis_assistant_write_controlled_ignored_project_manifest() {
    local repository_root="$1"
    local project_path="$2"
    local output_file="$3"
    local project_relative_path="${project_path#${repository_root}/}"
    local relative_file
    local absolute_file
    local file_mode
    local object_hash

    : > "${output_file}"
    while IFS= read -r -d '' relative_file; do
        mybatis_assistant_ignored_project_path_is_controlled \
            "${project_relative_path}" "${relative_file}" || continue
        absolute_file="${repository_root}/${relative_file}"
        if [[ -L "${absolute_file}" ]]; then
            file_mode="120000"
            object_hash="$(readlink "${absolute_file}" \
                | git -C "${repository_root}" hash-object --stdin)"
        elif [[ -x "${absolute_file}" ]]; then
            file_mode="100755"
            object_hash="$(git -C "${repository_root}" \
                hash-object --no-filters -- "${absolute_file}")"
        else
            file_mode="100644"
            object_hash="$(git -C "${repository_root}" \
                hash-object --no-filters -- "${absolute_file}")"
        fi
        printf '%s\t%s\t%s\n' "${file_mode}" "${object_hash}" "${relative_file}" \
            >> "${output_file}"
    done < <(git -C "${repository_root}" ls-files -z \
        --others --ignored --exclude-standard -- "${project_relative_path}")
}

mybatis_assistant_verify_project_unchanged() {
    local baseline_manifest="$1"
    local current_manifest="$2"
    local diff_file="$3"
    if cmp -s "${baseline_manifest}" "${current_manifest}"; then
        : > "${diff_file}"
        return 0
    fi
    diff -u "${baseline_manifest}" "${current_manifest}" > "${diff_file}" || true
    return 1
}

mybatis_assistant_verify_controlled_ignored_project_delta() {
    local baseline_manifest="$1"
    local current_manifest="$2"
    local unexpected_delta_file="$3"

    awk -F '\t' \
        -v baseline_file="${baseline_manifest}" \
        -v current_file="${current_manifest}" '
        BEGIN { OFS = "\t" }
        FILENAME == baseline_file { baseline[$3] = $1 FS $2; next }
        FILENAME != current_file { next }
        {
            current[$3] = $1 FS $2
            baseline_matches = (($3 in baseline) && baseline[$3] == current[$3])
            if (!baseline_matches) {
                print "changed_or_created", $0
            }
        }
        END {
            for (path in baseline) {
                if (!(path in current)) {
                    print "removed", path, baseline[path]
                }
            }
        }
    ' "${baseline_manifest}" "${current_manifest}" \
        | LC_ALL=C sort > "${unexpected_delta_file}"
    [[ ! -s "${unexpected_delta_file}" ]]
}

mybatis_assistant_sandbox_bytes() {
    local sandbox_root="$1"
    du -sk "${sandbox_root}" | awk '{ printf "%.0f\n", $1 * 1024 }'
}

mybatis_assistant_sandbox_file_count() {
    local sandbox_root="$1"
    find "${sandbox_root}" -type f -print | wc -l | tr -d '[:space:]'
}

mybatis_assistant_resource_trend_is_bounded() {
    local baseline_bytes="$1"
    local baseline_files="$2"
    local current_bytes="$3"
    local current_files="$4"
    local byte_growth=$((current_bytes - baseline_bytes))
    local file_growth=$((current_files - baseline_files))
    (( byte_growth <= MYBATIS_ASSISTANT_MAX_SANDBOX_GROWTH_BYTES \
        && file_growth <= MYBATIS_ASSISTANT_MAX_SANDBOX_FILE_GROWTH ))
}

mybatis_assistant_description_noise_line_is_known() {
    local line="$1"
    local tools="${line#*Descriptions are missed for tools: }"
    local remaining="${tools}"
    local tool
    local tool_count=0

    [[ "${line}" == *" SEVERE - #c.i.c.InspectionsResultUtil - Descriptions are missed for tools: "* ]] \
        || return 1
    while [[ -n "${remaining}" ]]; do
        if [[ "${remaining}" == *,* ]]; then
            tool="${remaining%%,*}"
            remaining="${remaining#*,}"
        else
            tool="${remaining}"
            remaining=""
        fi
        tool="$(printf '%s' "${tool}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
        [[ -n "${tool}" ]] || return 1
        case "${MYBATIS_ASSISTANT_DESCRIPTION_NOISE_TOOLS}" in
            *"|${tool}|"*) ;;
            *) return 1 ;;
        esac
        tool_count=$((tool_count + 1))
    done
    (( tool_count > 0 ))
}

mybatis_assistant_classify_ide_failures() {
    local log_file="$1"
    local platform_version="$2"
    local allowlisted_output="$3"
    local unexpected_output="$4"
    local line
    local active_logger=""
    local active_context_remaining=0

    : > "${allowlisted_output}"
    : > "${unexpected_output}"
    case "${platform_version}" in
        2025.2.6.2|2026.1.4) ;;
        *)
            grep -E '[[:space:]](ERROR|SEVERE)[[:space:]]+-' \
                "${log_file}" > "${unexpected_output}" || true
            return 0
            ;;
    esac

    while IFS= read -r line; do
        if [[ "${line}" != *" ERROR -"* && "${line}" != *" SEVERE -"* ]]; then
            if [[ -n "${active_logger}" && ${active_context_remaining} -gt 0 ]]; then
                active_context_remaining=$((active_context_remaining - 1))
            fi
            continue
        fi
        if mybatis_assistant_description_noise_line_is_known "${line}"; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_logger="InspectionsResultUtil"
            active_context_remaining=64
        elif [[ "${active_logger}" == "InspectionsResultUtil" \
            && ${active_context_remaining} -gt 0 \
            && ( "${line}" == *" SEVERE - #c.i.c.InspectionsResultUtil - IntelliJ IDEA ${platform_version}  Build #IU-"* \
                || "${line}" == *" SEVERE - #c.i.c.InspectionsResultUtil - JDK: "*"; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o."* \
                || "${line}" == *" SEVERE - #c.i.c.InspectionsResultUtil - OS: "* \
                || "${line}" == *" SEVERE - #c.i.c.InspectionsResultUtil - Last Action: "* ) ]]; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_context_remaining=$((active_context_remaining - 1))
        elif [[ "${platform_version}" == "2025.2.6.2" \
            && "${line}" == *" SEVERE - #c.i.d.LoadingState - Should be called at least in the state COMPONENTS_LOADED, the current state is: CONFIGURATION_STORE_INITIALIZED" ]]; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_logger="LoadingState"
            active_context_remaining=128
        elif [[ "${active_logger}" == "LoadingState" \
            && ${active_context_remaining} -gt 0 \
            && ( "${line}" == *" SEVERE - #c.i.d.LoadingState - IntelliJ IDEA 2025.2.6.2  Build #IU-252.28539.54"* \
                || "${line}" == *" SEVERE - #c.i.d.LoadingState - JDK: "*"; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o."* \
                || "${line}" == *" SEVERE - #c.i.d.LoadingState - OS: "* \
                || "${line}" == *" SEVERE - #c.i.d.LoadingState - Last Action: "* ) ]]; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_context_remaining=$((active_context_remaining - 1))
        elif [[ "${platform_version}" == "2026.1.4" \
            && "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - com.intellij.codeInspection.ex.QuickFixAction <clinit> requests com.intellij.notification.NotificationGroupManager instance. Class initialization must not depend on services. Consider using instance of the service on-demand instead." ]]; then
            # IDEA 2026.1.4 自带 Java 检查在离线 Inspection 初始化 Quick Fix 时
            # 会记录这一条平台错误；仅接受精确正文及紧随其后的 Java 插件归责块。
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_logger="QuickFixAction"
            active_context_remaining=128
        elif [[ "${active_logger}" == "QuickFixAction" \
            && ${active_context_remaining} -gt 0 \
            && ( "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - IntelliJ IDEA 2026.1.4  Build #IU-261.26222.65"* \
                || "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - JDK: "*"; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o."* \
                || "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - OS: "* \
                || "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - Plugin to blame: Java version: 261.26222.65"* \
                || "${line}" == *" SEVERE - #c.i.s.ComponentManagerImpl - Last Action: "* ) ]]; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
            active_context_remaining=$((active_context_remaining - 1))
        else
            printf '%s\n' "${line}" >> "${unexpected_output}"
            active_logger=""
            active_context_remaining=0
        fi
    done < "${log_file}"
}

mybatis_assistant_count_lines() {
    local file="$1"
    if [[ ! -s "${file}" ]]; then
        printf '0\n'
    else
        wc -l < "${file}" | tr -d '[:space:]'
    fi
}

mybatis_assistant_verify_inspection_output() {
    local inspection_output="$1"
    local evidence_file="$2"
    local expected_file="${inspection_output}/${MYBATIS_ASSISTANT_EXPECTED_INSPECTION}.xml"
    local descriptions_file="${inspection_output}/.descriptions.xml"
    local inspection_file
    local problem_block
    local normalized_problem_block
    local description_match_count
    local expected_problem_count
    local total_problem_count=0

    [[ -s "${descriptions_file}" ]] || return 1
    [[ -s "${expected_file}" ]] || return 1
    description_match_count="$({
        grep -oF -- \
            "<inspection shortName=\"${MYBATIS_ASSISTANT_EXPECTED_INSPECTION}\"" \
            "${descriptions_file}" || true
    } | wc -l | tr -d '[:space:]')"
    (( description_match_count == 1 )) || return 1
    expected_problem_count="$(grep -c '<problem>' "${expected_file}" || true)"
    (( expected_problem_count == 1 )) || return 1
    problem_block="$(awk '
        /<problem>/ {
            count++
            if (count > 1 || active) exit 2
            active = 1
        }
        active { print }
        /<\/problem>/ {
            if (!active) exit 2
            active = 0
            closed++
        }
        END {
            if (count != 1 || closed != 1 || active) exit 1
        }
    ' "${expected_file}")" || return 1
    normalized_problem_block="$(printf '%s\n' "${problem_block}" \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    printf '%s\n' "${normalized_problem_block}" | awk '
        /^<file>/ { file_count++ }
        /^<line>/ { line_count++ }
        /^<problem_class[ >]/ { class_count++ }
        /^<description>/ { description_count++ }
        /^<highlighted_element>/ { highlighted_count++ }
        END {
            exit !(file_count == 1 && line_count == 1 && class_count == 1 \
                && description_count == 1 && highlighted_count == 1)
        }
    ' || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Fxq -- \
        "<file>file://\$PROJECT_DIR\$/${MYBATIS_ASSISTANT_EXPECTED_SOURCE}</file>" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" \
        | grep -Eq '^<line>[1-9][0-9]*</line>$' || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Eq -- \
        "^<problem_class id=\"${MYBATIS_ASSISTANT_EXPECTED_INSPECTION}\"([[:space:]][^>]*)?>statement 未找到 Mapper 方法</problem_class>$" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Fxq -- \
        "<description>未找到对应的 Java Mapper 方法：${MYBATIS_ASSISTANT_EXPECTED_NAMESPACE}.${MYBATIS_ASSISTANT_EXPECTED_STATEMENT}</description>" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Fxq -- \
        "<highlighted_element>&quot;${MYBATIS_ASSISTANT_EXPECTED_STATEMENT}&quot;</highlighted_element>" \
        || return 1

    while IFS= read -r inspection_file; do
        total_problem_count=$((total_problem_count \
            + $(grep -c '<problem>' "${inspection_file}" || true)))
    done < <(find "${inspection_output}" -maxdepth 1 -type f \
        -name 'MyBatis*.xml' -print | LC_ALL=C sort)
    (( total_problem_count == 1 )) || return 1

    printf 'contract_version\texpected_inspection\texpected_problem_count\ttotal_mybatis_problem_count\texpected_source\texpected_symbol\n' \
        > "${evidence_file}"
    printf '%s\t%s\t%s\t%s\t%s\t%s.%s\n' \
        "${MYBATIS_ASSISTANT_IDE_NOISE_ALLOWLIST_VERSION}" \
        "${MYBATIS_ASSISTANT_EXPECTED_INSPECTION}" \
        "${expected_problem_count}" \
        "${total_problem_count}" \
        "${MYBATIS_ASSISTANT_EXPECTED_SOURCE}" \
        "${MYBATIS_ASSISTANT_EXPECTED_NAMESPACE}" \
        "${MYBATIS_ASSISTANT_EXPECTED_STATEMENT}" >> "${evidence_file}"
    # upload-artifact 默认忽略隐藏文件，复制一份非隐藏描述元数据供远端审计。
    cp "${descriptions_file}" "${evidence_file%.tsv}-descriptions.xml"
}

mybatis_assistant_prepare_lifecycle_settings() {
    local settings_file="$1"
    local mcp_port="$2"
    local temporary_file

    MYBATIS_ASSISTANT_SETTINGS_FILE="${settings_file}"
    MYBATIS_ASSISTANT_SETTINGS_BACKUP="$(mktemp \
        "${TMPDIR:-/tmp}/mybatis-assistant-settings.XXXXXX")"
    chmod 600 "${MYBATIS_ASSISTANT_SETTINGS_BACKUP}"
    if [[ -f "${settings_file}" ]]; then
        cp "${settings_file}" "${MYBATIS_ASSISTANT_SETTINGS_BACKUP}"
        MYBATIS_ASSISTANT_SETTINGS_EXISTED=1
    else
        MYBATIS_ASSISTANT_SETTINGS_EXISTED=0
    fi

    mkdir -p "$(dirname "${settings_file}")"
    temporary_file="$(mktemp "${settings_file}.tmp.XXXXXX")"
    chmod 600 "${temporary_file}"
    {
        printf '%s\n' '<application>'
        printf '%s\n' '  <component name="io.github.ns3154.mybatisassistant.settings.MyBatisAssistantSettings">'
        printf '%s\n' '    <option name="schemaVersion" value="2" />'
        printf '%s\n' '    <option name="allowNetworkAccess" value="false" />'
        printf '%s\n' '    <option name="mcpEnabled" value="true" />'
        printf '    <option name="mcpPort" value="%s" />\n' "${mcp_port}"
        printf '%s\n' '    <option name="mcpWriteToolsEnabled" value="false" />'
        printf '%s\n' '  </component>'
        printf '%s\n' '</application>'
    } > "${temporary_file}"
    mv "${temporary_file}" "${settings_file}"
}

mybatis_assistant_restore_lifecycle_settings() {
    if [[ -n "${MYBATIS_ASSISTANT_SETTINGS_FILE}" ]]; then
        if (( MYBATIS_ASSISTANT_SETTINGS_EXISTED == 1 )); then
            cp "${MYBATIS_ASSISTANT_SETTINGS_BACKUP}" "${MYBATIS_ASSISTANT_SETTINGS_FILE}"
        else
            rm -f -- "${MYBATIS_ASSISTANT_SETTINGS_FILE}"
        fi
    fi
    if [[ -n "${MYBATIS_ASSISTANT_SETTINGS_BACKUP}" ]]; then
        rm -f -- "${MYBATIS_ASSISTANT_SETTINGS_BACKUP}"
    fi
    MYBATIS_ASSISTANT_SETTINGS_FILE=""
    MYBATIS_ASSISTANT_SETTINGS_BACKUP=""
    MYBATIS_ASSISTANT_SETTINGS_EXISTED=0
}

mybatis_assistant_lifecycle_cleanup() {
    if [[ -n "${MYBATIS_ASSISTANT_ACTIVE_RUN_PID}" ]]; then
        mybatis_assistant_terminate_scoped_run \
            "${MYBATIS_ASSISTANT_ACTIVE_RUN_PID}" \
            "${MYBATIS_ASSISTANT_ACTIVE_RUN_STARTED_AT}" \
            "${MYBATIS_ASSISTANT_ACTIVE_RUN_COMMAND}" \
            "${MYBATIS_ASSISTANT_ACTIVE_IDE_PID}" \
            "${MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT}" \
            "${MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND}" \
            "${MYBATIS_ASSISTANT_ACTIVE_PROCESS_IDENTITIES}"
        wait "${MYBATIS_ASSISTANT_ACTIVE_RUN_PID}" 2>/dev/null || true
    fi
    MYBATIS_ASSISTANT_ACTIVE_RUN_PID=""
    MYBATIS_ASSISTANT_ACTIVE_RUN_STARTED_AT=""
    MYBATIS_ASSISTANT_ACTIVE_RUN_COMMAND=""
    MYBATIS_ASSISTANT_ACTIVE_IDE_PID=""
    MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT=""
    MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND=""
    MYBATIS_ASSISTANT_ACTIVE_PROCESS_IDENTITIES=""
    mybatis_assistant_restore_lifecycle_settings
}

mybatis_assistant_lifecycle_main() {
    local project_root="${MYBATIS_ASSISTANT_LIFECYCLE_PROJECT_ROOT}"
    local cycle_count="${1:-20}"
    local project_path_input="${2:-}"
    local rebuild_indexes="${3:-false}"
    local platform_version_override="${4:-}"
    local report_dir="${project_root}/build/reports/lifecycle"
    local default_platform_version
    local platform_version
    local sandbox_root
    local sandbox_log
    local sandbox_index_dir
    local summary_file
    local inspection_profile="${project_root}/config/lifecycle-inspection-profile.xml"
    local mcp_port="${MYBATIS_ASSISTANT_LIFECYCLE_MCP_PORT:-39473}"
    local project_path=""
    local project_name=""
    local candidate_project_path
    local project_manifest_baseline=""
    local ignored_project_manifest_baseline=""
    local first_sandbox_bytes=0
    local first_sandbox_files=0
    local first_max_process_count=0
    local gradle_platform_argument
    local gradle_lock_argument
    local cycle

    default_platform_version="$(sed -n 's/^platformVersion=//p' \
        "${project_root}/gradle.properties")"
    platform_version="${platform_version_override:-${default_platform_version}}"
    sandbox_root="${project_root}/build/idea-sandbox/mybatis-idea-assistant/IU-${platform_version}"
    sandbox_log="${sandbox_root}/log/idea.log"
    sandbox_index_dir="${sandbox_root}/system/index"
    summary_file="${report_dir}/sandbox-lifecycle.tsv"

    if ! [[ "${cycle_count}" =~ ^[1-9][0-9]*$ ]]; then
        echo "循环次数必须是正整数：${cycle_count}" >&2
        return 2
    fi
    if [[ -z "${platform_version}" \
        || ! "${platform_version}" =~ ^[0-9]{4}\.[0-9]+([.][0-9]+)*$ ]]; then
        echo "IDE 版本格式不正确：${platform_version}" >&2
        return 2
    fi
    if [[ "${rebuild_indexes}" != "true" && "${rebuild_indexes}" != "false" ]]; then
        echo "索引重建参数只能是 true 或 false：${rebuild_indexes}" >&2
        return 2
    fi
    if [[ -n "${project_path_input}" ]]; then
        [[ -f "${inspection_profile}" ]] || {
            echo "生命周期检查配置不存在：${inspection_profile}" >&2
            return 2
        }
        if [[ "${project_path_input}" = /* ]]; then
            candidate_project_path="${project_path_input}"
        else
            candidate_project_path="${project_root}/${project_path_input}"
        fi
        [[ -d "${candidate_project_path}" ]] || {
            echo "生命周期项目目录不存在：${candidate_project_path}" >&2
            return 2
        }
        project_path="$(cd "${candidate_project_path}" && pwd)"
        case "${project_path}" in
            "${project_root}"/*) ;;
            *)
                echo "生命周期项目必须位于当前仓库内：${project_path}" >&2
                return 2
                ;;
        esac
        project_name="$(basename "${project_path}")"
        command -v lsof >/dev/null || {
            echo "生命周期 MCP 端口证据需要 lsof" >&2
            return 2
        }
        command -v curl >/dev/null || {
            echo "生命周期 MCP 协议指纹证据需要 curl" >&2
            return 2
        }
        if ! [[ "${mcp_port}" =~ ^[0-9]+$ ]] \
            || (( mcp_port < 1024 || mcp_port > 65535 )); then
            echo "生命周期 MCP 端口必须在 1024～65535：${mcp_port}" >&2
            return 2
        fi
    fi
    case "${sandbox_index_dir}" in
        "${project_root}"/build/idea-sandbox/*/system/index) ;;
        *)
            echo "拒绝使用不安全的沙箱索引目录：${sandbox_index_dir}" >&2
            return 2
            ;;
    esac

    mkdir -p "${report_dir}"
    printf 'cycle\tplatform_version\tnoise_allowlist\tstart_line\tshutdown_line\tplugin_loaded\tproject_opened\tindex_scan_completed\tindex_rebuilt\tproject_disposed\tinspection_verified\tinspection_problem_count\tversion_control_visible_files_unchanged\tcontrolled_ignored_artifacts_unchanged\tmcp_port_active\tmcp_protocol_verified\tmcp_port_released\tmax_ide_tree_process_count\torphan_process_count\tsandbox_bytes\tsandbox_files\tsandbox_growth_bytes\tsandbox_growth_files\tallowlisted_error_count\tunexpected_error_count\tplugin_error_count\texit_code\n' \
        > "${summary_file}"

    cd "${project_root}"
    gradle_platform_argument="-PplatformVersion=${platform_version}"
    gradle_lock_argument="-PdependencyLockFile=gradle/lifecycle-${platform_version}.lockfile"
    ./scripts/run-gradle-with-infrastructure-retry.sh \
        --no-daemon "${gradle_platform_argument}" "${gradle_lock_argument}" \
        prepareSandbox >/dev/null
    touch "${sandbox_log}"

    trap mybatis_assistant_lifecycle_cleanup EXIT
    trap 'exit 130' INT TERM
    if [[ -n "${project_path}" ]]; then
        if ! mybatis_assistant_port_is_free "${mcp_port}"; then
            echo "生命周期 MCP 端口已被占用：${mcp_port}" >&2
            return 1
        fi
        mybatis_assistant_prepare_lifecycle_settings \
            "${sandbox_root}/config/options/mybatisAssistant.xml" "${mcp_port}"
        project_manifest_baseline="${report_dir}/project-manifest-baseline.tsv"
        mybatis_assistant_write_project_manifest \
            "${project_root}" "${project_path}" "${project_manifest_baseline}"
        ignored_project_manifest_baseline="${report_dir}/controlled-ignored-project-manifest-baseline.tsv"
        mybatis_assistant_write_controlled_ignored_project_manifest \
            "${project_root}" "${project_path}" "${ignored_project_manifest_baseline}"
        # 未登记任何可变项：.idea、target、build、.gradle 都必须保持基线。
    fi

    for ((cycle = 1; cycle <= cycle_count; cycle++)); do
        local cycle_name
        local cycle_output
        local start_line
        local inspection_output=""
        local run_pid
        local ide_pid=""
        local run_exit_code
        local shutdown_line
        local cycle_slice
        local freeze_evidence
        local process_identity_file
        local live_process_file
        local plugin_loaded=0
        local project_opened=1
        local index_scan_completed=1
        local index_rebuilt=1
        local project_disposed=1
        local inspection_verified=1
        local inspection_problem_count=0
        local project_unchanged=1
        local controlled_ignored_artifacts_unchanged=1
        local mcp_port_active=1
        local mcp_protocol_verified=1
        local mcp_port_released=1
        local max_process_count=0
        local orphan_process_count=0
        local sandbox_bytes
        local sandbox_files
        local sandbox_growth_bytes
        local sandbox_growth_files
        local allowlisted_errors
        local unexpected_errors
        local allowlisted_error_count
        local unexpected_error_count
        local plugin_error_count
        local current_manifest
        local project_diff
        local current_ignored_project_manifest
        local ignored_project_delta
        local run_ide_command
        local run_started_at
        local run_command
        local ide_started_at=""
        local ide_command=""

        cycle_name="$(printf '%02d' "${cycle}")"
        cycle_output="${report_dir}/cycle-${cycle_name}.log"
        process_identity_file="${report_dir}/cycle-${cycle_name}-processes.tsv"
        live_process_file="${report_dir}/cycle-${cycle_name}-orphan-processes.tsv"
        freeze_evidence="${report_dir}/cycle-${cycle_name}-log-freeze.tsv"
        : > "${process_identity_file}"
        : > "${live_process_file}"
        : > "${sandbox_log}"
        start_line=1

        if [[ "${rebuild_indexes}" == "true" ]]; then
            # 只删除当前仓库 build 下已校验过的单一沙箱索引目录。
            rm -rf -- "${sandbox_index_dir}"
        fi
        if [[ -n "${project_path}" ]] && ! mybatis_assistant_port_is_free "${mcp_port}"; then
            echo "第 ${cycle} 次启动前 MCP 端口仍被占用：${mcp_port}" >&2
            return 1
        fi

        run_ide_command=(
            ./gradlew
            --no-daemon
            "${gradle_platform_argument}"
            "${gradle_lock_argument}"
            runIde
        )
        if [[ -n "${project_path}" ]]; then
            inspection_output="${report_dir}/inspection-${cycle_name}"
            case "${inspection_output}" in
                "${report_dir}"/inspection-[0-9]*) ;;
                *)
                    echo "拒绝重置不安全的检查输出目录：${inspection_output}" >&2
                    return 2
                    ;;
            esac
            rm -rf -- "${inspection_output}"
            mkdir -p "${inspection_output}"
            run_ide_command+=(
                "--args=inspect ${project_path} ${inspection_profile} ${inspection_output} -v2"
            )
        else
            run_ide_command+=("--args=exit")
        fi
        # 2026.1 在长稳反复启动时可能于 Registry 完成加载前读取
        # use.eel.file.watcher，并由平台自身记录 SEVERE。平台日志明确要求
        # 早期启动阶段使用同名系统属性；与隔离门保持一致固定为 false。
        JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-Duse.eel.file.watcher=false" \
            "${run_ide_command[@]}" > "${cycle_output}" 2>&1 &
        run_pid=$!
        MYBATIS_ASSISTANT_ACTIVE_RUN_PID="${run_pid}"
        MYBATIS_ASSISTANT_ACTIVE_PROCESS_IDENTITIES="${process_identity_file}"
        run_started_at="$(mybatis_assistant_wait_for_process_identity \
            "${run_pid}" 'gradle-wrapper.jar' 5 "${process_identity_file}" || true)"
        run_command="$(mybatis_assistant_process_command "${run_pid}" || true)"
        MYBATIS_ASSISTANT_ACTIVE_RUN_STARTED_AT="${run_started_at}"
        MYBATIS_ASSISTANT_ACTIVE_RUN_COMMAND="${run_command}"
        if [[ -z "${run_started_at}" || -z "${run_command}" \
            || "${run_command}" != *'gradle-wrapper.jar'* \
            || "${run_command}" != *'--no-daemon'* \
            || "${run_command}" != *'runIde'* ]]; then
            echo "第 ${cycle} 次未记录到预期 Gradle runIde 身份" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ACTIVE_IDE_PID=""
        MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT=""
        MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND=""
        printf '%s\t%s\t%s\n' \
            "${run_pid}" "${run_started_at}" "${run_command}" >> "${process_identity_file}"

        if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
            "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
            echo "第 ${cycle} 次启动未在 90 秒内加载插件，详见 ${cycle_output}" >&2
            return 1
        fi
        ide_pid="$(mybatis_assistant_find_ide_pid "${run_pid}" 30 || true)"
        if [[ -z "${ide_pid}" ]]; then
            echo "第 ${cycle} 次未解析到本轮 IDEA PID" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ACTIVE_IDE_PID="${ide_pid}"
        ide_started_at="$(mybatis_assistant_process_started_at "${ide_pid}" || true)"
        ide_command="$(mybatis_assistant_process_command "${ide_pid}" || true)"
        if [[ -z "${ide_started_at}" || -z "${ide_command}" \
            || ( "${ide_command}" != *'com.intellij.idea.Main'* \
                && "${ide_command}" != *'idea.platform.prefix=Idea'* ) ]]; then
            echo "第 ${cycle} 次 IDEA 身份记录不完整" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT="${ide_started_at}"
        MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND="${ide_command}"
        mybatis_assistant_record_process_tree \
            "${ide_pid}" "${process_identity_file}"

        if [[ -n "${project_path}" ]]; then
            if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
                "Project ${project_name} was added to the list of open projects" \
                "${start_line}" 120; then
                echo "第 ${cycle} 次启动未在 120 秒内打开项目 ${project_name}" >&2
                return 1
            fi
            if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
                "Scanning completed for [${project_name}]" "${start_line}" 180; then
                echo "第 ${cycle} 次启动未在 180 秒内完成项目索引扫描" >&2
                return 1
            fi
            if ! mybatis_assistant_wait_for_owned_mcp_port "${run_pid}" "${mcp_port}" 120; then
                echo "第 ${cycle} 次未证明 MCP 端口 ${mcp_port} 由本轮 IDEA 独占监听" >&2
                return 1
            fi
            if [[ "${MYBATIS_ASSISTANT_IDE_PID}" != "${ide_pid}" ]]; then
                echo "第 ${cycle} 次 MCP 监听 PID 不是已识别的 IDEA 主进程" >&2
                return 1
            fi
            if ! mybatis_assistant_probe_mcp_endpoint \
                "${mcp_port}" "${report_dir}/cycle-${cycle_name}-mcp"; then
                echo "第 ${cycle} 次端口不是预期的 MyBatis Assistant MCP 安全端点" >&2
                return 1
            fi
            if ! mybatis_assistant_observe_until_run_exit \
                "${run_pid}" "${ide_pid}" "${process_identity_file}" 600; then
                echo "第 ${cycle} 次项目检查未在 600 秒内退出" >&2
                return 1
            fi
            max_process_count="${MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT}"
        fi

        set +e
        wait "${run_pid}"
        run_exit_code=$?
        set -e

        cycle_slice="${report_dir}/cycle-${cycle_name}-idea.log"
        if ! shutdown_line="$(mybatis_assistant_freeze_lifecycle_log_after_process_exit \
            "${sandbox_log}" "${start_line}" \
            "${ide_pid}" "${ide_started_at}" "${ide_command}" \
            "${process_identity_file}" "${live_process_file}" \
            "${cycle_slice}" "${freeze_evidence}" 30)"; then
            orphan_process_count="$(mybatis_assistant_count_lines \
                "${live_process_file}")"
            echo "第 ${cycle} 次未在精确进程退出后冻结完整 IDEA 日志，详见 ${cycle_output}" >&2
            return 1
        fi

        if grep -Fq -- "Loaded custom plugins: MyBatis Assistant" "${cycle_slice}"; then
            plugin_loaded=1
        fi
        if [[ -n "${project_path}" ]]; then
            grep -Fq -- "Project ${project_name} was added to the list of open projects" \
                "${cycle_slice}" || project_opened=0
            grep -Fq -- "Scanning completed for [${project_name}]" \
                "${cycle_slice}" || index_scan_completed=0
            if [[ "${rebuild_indexes}" == "true" ]] && ! grep -Fq -- \
                "Full scanning on startup will NOT be skipped for project [${project_name}]" \
                "${cycle_slice}"; then
                index_rebuilt=0
            fi
            grep -Fq -- \
                "Project ${project_name} is removed from the list of initializing and open projects. Project was disposed." \
                "${cycle_slice}" || project_disposed=0

            if mybatis_assistant_verify_inspection_output \
                "${inspection_output}" \
                "${report_dir}/inspection-${cycle_name}-contract.tsv"; then
                inspection_problem_count=1
            else
                inspection_verified=0
            fi
            current_manifest="${report_dir}/project-manifest-${cycle_name}.tsv"
            project_diff="${report_dir}/project-manifest-${cycle_name}.diff"
            mybatis_assistant_write_project_manifest \
                "${project_root}" "${project_path}" "${current_manifest}"
            mybatis_assistant_verify_project_unchanged \
                "${project_manifest_baseline}" "${current_manifest}" "${project_diff}" \
                || project_unchanged=0
            current_ignored_project_manifest="${report_dir}/controlled-ignored-project-manifest-${cycle_name}.tsv"
            ignored_project_delta="${report_dir}/controlled-ignored-project-delta-${cycle_name}.tsv"
            mybatis_assistant_write_controlled_ignored_project_manifest \
                "${project_root}" "${project_path}" \
                "${current_ignored_project_manifest}"
            mybatis_assistant_verify_controlled_ignored_project_delta \
                "${ignored_project_manifest_baseline}" \
                "${current_ignored_project_manifest}" \
                "${ignored_project_delta}" \
                || controlled_ignored_artifacts_unchanged=0

            if ! mybatis_assistant_wait_for_port_release "${mcp_port}" 30; then
                mcp_port_released=0
            fi
        else
            inspection_verified=0
            mcp_port_active=0
            mcp_protocol_verified=0
            mcp_port_released=0
        fi

        allowlisted_errors="${report_dir}/cycle-${cycle_name}-allowlisted-errors.log"
        unexpected_errors="${report_dir}/cycle-${cycle_name}-unexpected-errors.log"
        mybatis_assistant_classify_ide_failures \
            "${cycle_slice}" "${platform_version}" \
            "${allowlisted_errors}" "${unexpected_errors}"
        allowlisted_error_count="$(mybatis_assistant_count_lines "${allowlisted_errors}")"
        unexpected_error_count="$(mybatis_assistant_count_lines "${unexpected_errors}")"
        plugin_error_count="$(grep -E -c \
            'ERROR .*MyBatis Assistant|PluginException.*io\.github\.ns3154|NoClassDefFoundError.*mybatisassistant|ClassNotFoundException.*mybatisassistant|^[[:space:]]+at io\.github\.ns3154\.mybatisassistant' \
            "${cycle_slice}" || true)"

        sandbox_bytes="$(mybatis_assistant_sandbox_bytes "${sandbox_root}")"
        sandbox_files="$(mybatis_assistant_sandbox_file_count "${sandbox_root}")"
        if (( cycle == 1 )); then
            first_sandbox_bytes="${sandbox_bytes}"
            first_sandbox_files="${sandbox_files}"
            first_max_process_count="${max_process_count}"
        fi
        sandbox_growth_bytes=$((sandbox_bytes - first_sandbox_bytes))
        sandbox_growth_files=$((sandbox_files - first_sandbox_files))

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "${cycle}" "${platform_version}" \
            "${MYBATIS_ASSISTANT_IDE_NOISE_ALLOWLIST_VERSION}" \
            "${start_line}" "${shutdown_line}" "${plugin_loaded}" \
            "${project_opened}" "${index_scan_completed}" "${index_rebuilt}" \
            "${project_disposed}" "${inspection_verified}" \
            "${inspection_problem_count}" "${project_unchanged}" \
            "${controlled_ignored_artifacts_unchanged}" \
            "${mcp_port_active}" "${mcp_protocol_verified}" \
            "${mcp_port_released}" "${max_process_count}" \
            "${orphan_process_count}" "${sandbox_bytes}" "${sandbox_files}" \
            "${sandbox_growth_bytes}" "${sandbox_growth_files}" \
            "${allowlisted_error_count}" "${unexpected_error_count}" \
            "${plugin_error_count}" "${run_exit_code}" >> "${summary_file}"

        if (( cycle > 1 )); then
            mybatis_assistant_resource_trend_is_bounded \
                "${first_sandbox_bytes}" "${first_sandbox_files}" \
                "${sandbox_bytes}" "${sandbox_files}" || {
                echo "第 ${cycle} 次沙箱资源增长超过有界阈值" >&2
                return 1
            }
            if (( max_process_count > first_max_process_count \
                + MYBATIS_ASSISTANT_MAX_IDE_PROCESS_GROWTH )); then
                echo "第 ${cycle} 次 IDEA 进程树增长超过有界阈值" >&2
                return 1
            fi
        fi

        if (( run_exit_code != 0 \
            || plugin_loaded != 1 \
            || project_opened != 1 \
            || index_scan_completed != 1 \
            || index_rebuilt != 1 \
            || project_disposed != 1 \
            || plugin_error_count != 0 \
            || unexpected_error_count != 0 )); then
            echo "第 ${cycle} 次生命周期失败，详见 ${cycle_slice}" >&2
            return 1
        fi
        if [[ -n "${project_path}" ]] \
            && (( inspection_verified != 1 \
                || inspection_problem_count != 1 \
                || project_unchanged != 1 \
                || controlled_ignored_artifacts_unchanged != 1 \
                || mcp_port_active != 1 \
                || mcp_protocol_verified != 1 \
                || mcp_port_released != 1 \
                || max_process_count < 1 \
                || orphan_process_count != 0 )); then
            echo "第 ${cycle} 次交付契约失败，详见 ${report_dir}" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ACTIVE_RUN_PID=""
        MYBATIS_ASSISTANT_ACTIVE_RUN_STARTED_AT=""
        MYBATIS_ASSISTANT_ACTIVE_RUN_COMMAND=""
        MYBATIS_ASSISTANT_ACTIVE_IDE_PID=""
        MYBATIS_ASSISTANT_ACTIVE_IDE_STARTED_AT=""
        MYBATIS_ASSISTANT_ACTIVE_IDE_COMMAND=""
        MYBATIS_ASSISTANT_ACTIVE_PROCESS_IDENTITIES=""
    done

    mybatis_assistant_restore_lifecycle_settings
    trap - EXIT INT TERM
    if [[ -n "${project_path}" ]]; then
        echo "沙箱生命周期验证通过：${cycle_count}/${cycle_count}；逐轮检查、版本控制可见文件不变、受控 ignored 项目产物无增量、MCP 端口、进程树与资源趋势均已验证；报告：${summary_file}"
    else
        echo "沙箱基础启停验证通过：${cycle_count}/${cycle_count}；未提供项目，未执行交付级检查与 MCP 断言；报告：${summary_file}"
    fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    mybatis_assistant_lifecycle_main "$@"
fi
