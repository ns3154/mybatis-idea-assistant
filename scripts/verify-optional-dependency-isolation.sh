#!/usr/bin/env bash

set -euo pipefail

readonly MYBATIS_ASSISTANT_ISOLATION_PROJECT_ROOT="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd
)"
# 复用生命周期脚本中的检查产物、项目清洁度、进程与版本化日志分类契约。
source "${MYBATIS_ASSISTANT_ISOLATION_PROJECT_ROOT}/scripts/verify-sandbox-lifecycle.sh"

readonly MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES=(
    "kotlin"
    "spring"
    "yaml"
    "database"
    "all"
)
readonly MYBATIS_ASSISTANT_ISOLATION_PLUGIN_IDS=(
    "org.jetbrains.kotlin"
    "com.intellij.spring"
    "org.jetbrains.plugins.yaml"
    "com.intellij.database"
    "org.jetbrains.kotlin,com.intellij.spring,org.jetbrains.plugins.yaml,com.intellij.database"
)
readonly MYBATIS_ASSISTANT_ISOLATION_PLUGIN_NAMES=(
    "Kotlin"
    "Spring"
    "YAML"
    "Database Tools and SQL"
    "Kotlin,Spring,YAML,Database Tools and SQL"
)

MYBATIS_ASSISTANT_ISOLATION_DISABLED_FILE=""
MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_STARTED_AT=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_COMMAND=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND=""
MYBATIS_ASSISTANT_ISOLATION_ACTIVE_PROCESS_IDENTITIES=""

mybatis_assistant_plugin_inventory_contains() {
    local inventory="$1"
    local plugin_name="$2"
    [[ "${inventory}" == *"plugins: ${plugin_name} ("* \
        || "${inventory}" == *", ${plugin_name} ("* ]]
}

mybatis_assistant_verify_plugin_isolation() {
    local idea_log="$1"
    local disabled_plugin_names="$2"
    local evidence_file="$3"
    local loaded_inventory
    local disabled_inventory
    local remaining="${disabled_plugin_names}"
    local plugin_name

    loaded_inventory="$(grep -F -- 'Loaded bundled plugins:' "${idea_log}" | tail -n 1)"
    disabled_inventory="$(grep -F -- 'Disabled plugins:' "${idea_log}" | tail -n 1)"
    [[ -n "${loaded_inventory}" && -n "${disabled_inventory}" ]] || return 1
    grep -Fq -- 'Loaded custom plugins: MyBatis Assistant' "${idea_log}" || return 1
    mybatis_assistant_plugin_inventory_contains "${loaded_inventory}" 'IDEA CORE' \
        || return 1
    mybatis_assistant_plugin_inventory_contains "${loaded_inventory}" 'Java' \
        || return 1

    printf 'plugin_name\treported_disabled\tabsent_from_loaded_inventory\n' \
        > "${evidence_file}"
    while [[ -n "${remaining}" ]]; do
        if [[ "${remaining}" == *,* ]]; then
            plugin_name="${remaining%%,*}"
            remaining="${remaining#*,}"
        else
            plugin_name="${remaining}"
            remaining=""
        fi
        [[ -n "${plugin_name}" ]] || return 1
        mybatis_assistant_plugin_inventory_contains "${disabled_inventory}" "${plugin_name}" \
            || return 1
        if mybatis_assistant_plugin_inventory_contains "${loaded_inventory}" "${plugin_name}"; then
            return 1
        fi
        printf '%s\t1\t1\n' "${plugin_name}" >> "${evidence_file}"
    done
}

mybatis_assistant_restore_disabled_plugins() {
    if [[ -n "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_FILE}" \
        && -n "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP}" ]]; then
        cp "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP}" \
            "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_FILE}"
    fi
    if [[ -n "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP}" ]]; then
        rm -f -- "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP}"
    fi
    MYBATIS_ASSISTANT_ISOLATION_DISABLED_FILE=""
    MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP=""
}

mybatis_assistant_isolation_cleanup() {
    if [[ -n "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID}" ]]; then
        mybatis_assistant_terminate_scoped_run \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_STARTED_AT}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_COMMAND}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND}" \
            "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_PROCESS_IDENTITIES}"
        wait "${MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID}" 2>/dev/null || true
    fi
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_STARTED_AT=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_COMMAND=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND=""
    MYBATIS_ASSISTANT_ISOLATION_ACTIVE_PROCESS_IDENTITIES=""
    mybatis_assistant_restore_disabled_plugins
}

mybatis_assistant_optional_dependency_isolation_main() {
    local project_root="${MYBATIS_ASSISTANT_ISOLATION_PROJECT_ROOT}"
    local platform_version
    local sandbox_root
    local sandbox_log
    local disabled_plugins_file
    local report_dir="${project_root}/build/reports/optional-dependency-isolation"
    local summary_file="${report_dir}/optional-dependency-isolation.tsv"
    local inspection_profile="${project_root}/config/lifecycle-inspection-profile.xml"
    local semantic_project="${project_root}/samples/semantic-corpus"
    local semantic_project_name="semantic-corpus"
    local baseline_project_manifest="${report_dir}/project-manifest-baseline.tsv"
    local baseline_ignored_project_manifest="${report_dir}/controlled-ignored-project-manifest-baseline.tsv"
    local first_sandbox_bytes=0
    local first_sandbox_files=0
    local first_max_process_count=0
    local index

    platform_version="$(sed -n 's/^platformVersion=//p' \
        "${project_root}/gradle.properties")"
    if [[ -z "${platform_version}" ]]; then
        echo "gradle.properties 缺少 platformVersion" >&2
        return 2
    fi
    case "${platform_version}" in
        2025.2.6.2) ;;
        *)
            echo "可选依赖隔离噪声契约仅登记 2025.2.6.2，当前为 ${platform_version}" >&2
            return 2
            ;;
    esac
    [[ -d "${semantic_project}" ]] || {
        echo "语义项目不存在：${semantic_project}" >&2
        return 2
    }
    [[ -f "${inspection_profile}" ]] || {
        echo "检查配置不存在：${inspection_profile}" >&2
        return 2
    }

    sandbox_root="${project_root}/build/idea-sandbox/mybatis-idea-assistant/IU-${platform_version}"
    sandbox_log="${sandbox_root}/log/idea.log"
    disabled_plugins_file="${sandbox_root}/config/disabled_plugins.txt"
    mkdir -p "${report_dir}"

    cd "${project_root}"
    ./scripts/run-gradle-with-infrastructure-retry.sh \
        --no-daemon prepareSandbox >/dev/null
    touch "${sandbox_log}" "${disabled_plugins_file}"

    MYBATIS_ASSISTANT_ISOLATION_DISABLED_FILE="${disabled_plugins_file}"
    MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP="$(mktemp \
        "${TMPDIR:-/tmp}/mybatis-assistant-disabled-plugins.XXXXXX")"
    cp "${disabled_plugins_file}" "${MYBATIS_ASSISTANT_ISOLATION_DISABLED_BACKUP}"
    trap mybatis_assistant_isolation_cleanup EXIT
    trap 'exit 130' INT TERM

    mybatis_assistant_write_project_manifest \
        "${project_root}" "${semantic_project}" "${baseline_project_manifest}"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${project_root}" "${semantic_project}" "${baseline_ignored_project_manifest}"
    printf 'case\tplatform_version\tnoise_allowlist\tdisabled_plugin_ids\tdisabled_state_verified\tplugin_loaded\tproject_opened\tindex_scan_completed\tproject_disposed\tinspection_verified\tinspection_problem_count\tversion_control_visible_files_unchanged\tcontrolled_ignored_artifacts_unchanged\tmax_ide_tree_process_count\torphan_process_count\tsandbox_bytes\tsandbox_files\tsandbox_growth_bytes\tsandbox_growth_files\tallowlisted_error_count\tunexpected_error_count\tplugin_error_count\texit_code\n' \
        > "${summary_file}"

    for index in "${!MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES[@]}"; do
        local case_name="${MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES[${index}]}"
        local plugin_ids="${MYBATIS_ASSISTANT_ISOLATION_PLUGIN_IDS[${index}]}"
        local plugin_names="${MYBATIS_ASSISTANT_ISOLATION_PLUGIN_NAMES[${index}]}"
        local case_output="${report_dir}/${case_name}.log"
        local case_idea_log="${report_dir}/${case_name}-idea.log"
        local inspection_output="${report_dir}/inspection-${case_name}"
        local requested_plugin_file="${report_dir}/${case_name}-requested-plugin-ids.txt"
        local plugin_evidence="${report_dir}/${case_name}-plugin-state.tsv"
        local inspection_evidence="${report_dir}/${case_name}-inspection-contract.tsv"
        local process_identity_file="${report_dir}/${case_name}-processes.tsv"
        local live_process_file="${report_dir}/${case_name}-orphan-processes.tsv"
        local current_project_manifest="${report_dir}/project-manifest-${case_name}.tsv"
        local project_diff="${report_dir}/project-manifest-${case_name}.diff"
        local current_ignored_project_manifest="${report_dir}/controlled-ignored-project-manifest-${case_name}.tsv"
        local ignored_project_delta="${report_dir}/controlled-ignored-project-delta-${case_name}.tsv"
        local allowlisted_errors="${report_dir}/${case_name}-allowlisted-errors.log"
        local unexpected_errors="${report_dir}/${case_name}-unexpected-errors.log"
        local start_line=1
        local run_pid
        local run_started_at
        local run_command
        local ide_pid
        local ide_started_at
        local ide_command
        local run_exit_code
        local shutdown_relative_line
        local shutdown_line
        local disabled_state_verified=0
        local plugin_loaded=0
        local project_opened=0
        local index_scan_completed=0
        local project_disposed=0
        local inspection_verified=0
        local inspection_problem_count=0
        local project_unchanged=0
        local controlled_ignored_artifacts_unchanged=0
        local max_process_count=0
        local orphan_process_count=0
        local sandbox_bytes
        local sandbox_files
        local sandbox_growth_bytes
        local sandbox_growth_files
        local allowlisted_error_count
        local unexpected_error_count
        local plugin_error_count

        printf '%s' "${plugin_ids}" | tr ',' '\n' > "${requested_plugin_file}"
        cp "${requested_plugin_file}" "${disabled_plugins_file}"
        : > "${sandbox_log}"
        : > "${process_identity_file}"
        : > "${live_process_file}"
        case "${inspection_output}" in
            "${report_dir}"/inspection-*) ;;
            *)
                echo "拒绝重置不安全的隔离检查目录：${inspection_output}" >&2
                return 2
                ;;
        esac
        rm -rf -- "${inspection_output}"
        mkdir -p "${inspection_output}"

        ./gradlew --no-daemon runIde \
            "--args=inspect ${semantic_project} ${inspection_profile} ${inspection_output} -v2" \
            > "${case_output}" 2>&1 &
        run_pid=$!
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID="${run_pid}"
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_PROCESS_IDENTITIES="${process_identity_file}"
        run_started_at="$(mybatis_assistant_wait_for_process_identity \
            "${run_pid}" 'gradle-wrapper.jar' 5 "${process_identity_file}" || true)"
        run_command="$(mybatis_assistant_process_command "${run_pid}" || true)"
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_STARTED_AT="${run_started_at}"
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_COMMAND="${run_command}"
        if [[ -z "${run_started_at}" || -z "${run_command}" \
            || "${run_command}" != *'gradle-wrapper.jar'* \
            || "${run_command}" != *'--no-daemon'* \
            || "${run_command}" != *'runIde'* ]]; then
            echo "可选依赖隔离场景 ${case_name} 未记录到预期 Gradle runIde 身份" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND=""
        printf '%s\t%s\t%s\n' \
            "${run_pid}" "${run_started_at}" "${run_command}" >> "${process_identity_file}"

        if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
            "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
            echo "可选依赖隔离场景 ${case_name} 未在 90 秒内加载插件" >&2
            return 1
        fi
        ide_pid="$(mybatis_assistant_find_ide_pid "${run_pid}" 30 || true)"
        if [[ -z "${ide_pid}" ]]; then
            echo "可选依赖隔离场景 ${case_name} 未解析到本轮 IDEA PID" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID="${ide_pid}"
        ide_started_at="$(mybatis_assistant_process_started_at "${ide_pid}" || true)"
        ide_command="$(mybatis_assistant_process_command "${ide_pid}" || true)"
        if [[ -z "${ide_started_at}" || -z "${ide_command}" \
            || ( "${ide_command}" != *'com.intellij.idea.Main'* \
                && "${ide_command}" != *'idea.platform.prefix=Idea'* ) ]]; then
            echo "可选依赖隔离场景 ${case_name} 的 IDEA 身份记录不完整" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT="${ide_started_at}"
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND="${ide_command}"
        if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
            "Project ${semantic_project_name} was added to the list of open projects" \
            "${start_line}" 120; then
            echo "可选依赖隔离场景 ${case_name} 未打开语义项目" >&2
            return 1
        fi
        if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
            "Scanning completed for [${semantic_project_name}]" "${start_line}" 180; then
            echo "可选依赖隔离场景 ${case_name} 未完成语义项目索引" >&2
            return 1
        fi
        mybatis_assistant_record_process_tree "${ide_pid}" "${process_identity_file}"
        if ! mybatis_assistant_observe_until_run_exit \
            "${run_pid}" "${ide_pid}" "${process_identity_file}" 600; then
            echo "可选依赖隔离场景 ${case_name} 未在 600 秒内完成真实检查" >&2
            return 1
        fi
        max_process_count="${MYBATIS_ASSISTANT_OBSERVED_MAX_PROCESS_COUNT}"

        set +e
        wait "${run_pid}"
        run_exit_code=$?
        set -e

        if ! mybatis_assistant_wait_for_pattern "${sandbox_log}" \
            "IDE SHUTDOWN" "${start_line}" 30; then
            echo "可选依赖隔离场景 ${case_name} 未记录 IDE SHUTDOWN" >&2
            return 1
        fi
        shutdown_relative_line="$(tail -n "+${start_line}" "${sandbox_log}" \
            | grep -n -- "IDE SHUTDOWN" | tail -n 1 | cut -d: -f1)"
        shutdown_line=$((start_line + shutdown_relative_line - 1))
        sed -n "${start_line},${shutdown_line}p" "${sandbox_log}" > "${case_idea_log}"

        if cmp -s "${requested_plugin_file}" "${disabled_plugins_file}" \
            && mybatis_assistant_verify_plugin_isolation \
                "${case_idea_log}" "${plugin_names}" "${plugin_evidence}"; then
            disabled_state_verified=1
        fi
        grep -Fq -- "Loaded custom plugins: MyBatis Assistant" "${case_idea_log}" \
            && plugin_loaded=1
        grep -Fq -- \
            "Project ${semantic_project_name} was added to the list of open projects" \
            "${case_idea_log}" && project_opened=1
        grep -Fq -- "Scanning completed for [${semantic_project_name}]" \
            "${case_idea_log}" && index_scan_completed=1
        grep -Fq -- \
            "Project ${semantic_project_name} is removed from the list of initializing and open projects. Project was disposed." \
            "${case_idea_log}" && project_disposed=1
        if mybatis_assistant_verify_inspection_output \
            "${inspection_output}" "${inspection_evidence}"; then
            inspection_verified=1
            inspection_problem_count=1
        fi

        mybatis_assistant_write_project_manifest \
            "${project_root}" "${semantic_project}" "${current_project_manifest}"
        if mybatis_assistant_verify_project_unchanged \
            "${baseline_project_manifest}" "${current_project_manifest}" "${project_diff}"; then
            project_unchanged=1
        fi
        mybatis_assistant_write_controlled_ignored_project_manifest \
            "${project_root}" "${semantic_project}" \
            "${current_ignored_project_manifest}"
        if mybatis_assistant_verify_controlled_ignored_project_delta \
            "${baseline_ignored_project_manifest}" \
            "${current_ignored_project_manifest}" \
            "${ignored_project_delta}"; then
            controlled_ignored_artifacts_unchanged=1
        fi
        if ! mybatis_assistant_wait_for_recorded_processes_exit \
            "${process_identity_file}" "${live_process_file}" 30; then
            orphan_process_count="$(mybatis_assistant_count_lines "${live_process_file}")"
        fi

        mybatis_assistant_classify_ide_failures \
            "${case_idea_log}" "${platform_version}" \
            "${allowlisted_errors}" "${unexpected_errors}"
        allowlisted_error_count="$(mybatis_assistant_count_lines "${allowlisted_errors}")"
        unexpected_error_count="$(mybatis_assistant_count_lines "${unexpected_errors}")"
        plugin_error_count="$(grep -E -c \
            'ERROR .*MyBatis Assistant|PluginException.*io\.github\.ns3154|NoClassDefFoundError.*mybatisassistant|ClassNotFoundException.*mybatisassistant|^[[:space:]]+at io\.github\.ns3154\.mybatisassistant' \
            "${case_idea_log}" || true)"

        sandbox_bytes="$(mybatis_assistant_sandbox_bytes "${sandbox_root}")"
        sandbox_files="$(mybatis_assistant_sandbox_file_count "${sandbox_root}")"
        if (( index == 0 )); then
            first_sandbox_bytes="${sandbox_bytes}"
            first_sandbox_files="${sandbox_files}"
            first_max_process_count="${max_process_count}"
        fi
        sandbox_growth_bytes=$((sandbox_bytes - first_sandbox_bytes))
        sandbox_growth_files=$((sandbox_files - first_sandbox_files))

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "${case_name}" "${platform_version}" \
            "${MYBATIS_ASSISTANT_IDE_NOISE_ALLOWLIST_VERSION}" "${plugin_ids}" \
            "${disabled_state_verified}" "${plugin_loaded}" "${project_opened}" \
            "${index_scan_completed}" "${project_disposed}" \
            "${inspection_verified}" "${inspection_problem_count}" \
            "${project_unchanged}" "${controlled_ignored_artifacts_unchanged}" \
            "${max_process_count}" "${orphan_process_count}" \
            "${sandbox_bytes}" "${sandbox_files}" "${sandbox_growth_bytes}" \
            "${sandbox_growth_files}" "${allowlisted_error_count}" \
            "${unexpected_error_count}" "${plugin_error_count}" "${run_exit_code}" \
            >> "${summary_file}"

        if (( index > 0 )); then
            mybatis_assistant_resource_trend_is_bounded \
                "${first_sandbox_bytes}" "${first_sandbox_files}" \
                "${sandbox_bytes}" "${sandbox_files}" || {
                echo "可选依赖隔离场景 ${case_name} 的沙箱资源增长超过阈值" >&2
                return 1
            }
            if (( max_process_count > first_max_process_count \
                + MYBATIS_ASSISTANT_MAX_IDE_PROCESS_GROWTH )); then
                echo "可选依赖隔离场景 ${case_name} 的进程树增长超过阈值" >&2
                return 1
            fi
        fi
        if (( run_exit_code != 0 \
            || disabled_state_verified != 1 \
            || plugin_loaded != 1 \
            || project_opened != 1 \
            || index_scan_completed != 1 \
            || project_disposed != 1 \
            || inspection_verified != 1 \
            || inspection_problem_count != 1 \
            || project_unchanged != 1 \
            || controlled_ignored_artifacts_unchanged != 1 \
            || max_process_count < 1 \
            || orphan_process_count != 0 \
            || unexpected_error_count != 0 \
            || plugin_error_count != 0 )); then
            echo "可选依赖隔离场景 ${case_name} 失败，详见 ${report_dir}" >&2
            return 1
        fi
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_PID=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_STARTED_AT=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_RUN_COMMAND=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_PID=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_STARTED_AT=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_IDE_COMMAND=""
        MYBATIS_ASSISTANT_ISOLATION_ACTIVE_PROCESS_IDENTITIES=""
    done

    mybatis_assistant_restore_disabled_plugins
    trap - EXIT INT TERM
    echo "可选依赖隔离验证通过：${#MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES[@]}/${#MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES[@]}；每场景均证明目标插件未加载、完成真实语义检查、版本控制可见文件不变且受控 ignored 项目产物无增量；报告：${summary_file}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    mybatis_assistant_optional_dependency_isolation_main "$@"
fi
