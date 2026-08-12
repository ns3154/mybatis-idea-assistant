#!/usr/bin/env bash

set -euo pipefail

readonly TEST_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${TEST_PROJECT_ROOT}/scripts/verify-sandbox-lifecycle.sh"

readonly TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-lifecycle-contract.XXXXXX")"

cleanup() {
    case "${TEST_ROOT}" in
        "${TMPDIR:-/tmp}"/mybatis-lifecycle-contract.*) rm -rf -- "${TEST_ROOT}" ;;
        *)
            echo "拒绝清理非测试目录：${TEST_ROOT}" >&2
            return 1
            ;;
    esac
}
trap cleanup EXIT

assert_file_empty() {
    local file="$1"
    [[ ! -s "${file}" ]] || {
        echo "文件应为空：${file}" >&2
        return 1
    }
}

assert_file_contains() {
    local file="$1"
    local expected="$2"
    grep -Fq -- "${expected}" "${file}" || {
        echo "文件缺少预期内容 ${expected}：${file}" >&2
        return 1
    }
}

test_inspection_contract() {
    local output="${TEST_ROOT}/inspection"
    local evidence="${TEST_ROOT}/inspection-contract.tsv"
    mkdir -p "${output}"
    printf '%s\n' '<inspections><inspection shortName="MyBatisUnusedStatement"/></inspections>' \
        > "${output}/.descriptions.xml"
    {
        printf '%s\n' '<problems>'
        printf '%s\n' '  <problem>'
        printf '%s\n' '    <file>file://spring-application/src/main/resources/mappers/UserMapper.xml</file>'
        printf '%s\n' '    <line>103</line>'
        printf '%s\n' '    <problem_class severity="WARNING">statement 未找到 Mapper 方法</problem_class>'
        printf '%s\n' '    <description>未找到对应的 Java Mapper 方法：io.github.mybatisideaassistant.corpus.java.mapper.UserMapper.findSummary</description>'
        printf '%s\n' '  </problem>'
        printf '%s\n' '</problems>'
    } > "${output}/MyBatisUnusedStatement.xml"

    mybatis_assistant_verify_inspection_output "${output}" "${evidence}"
    assert_file_contains "${evidence}" $'2026-08-12.v2\tMyBatisUnusedStatement\t1\t1'
    [[ -s "${TEST_ROOT}/inspection-contract-descriptions.xml" ]]

    {
        printf '%s\n' '<problems>'
        printf '%s\n' '  <problem><line>1</line></problem>'
        printf '%s\n' '</problems>'
    } > "${output}/MyBatisMissingStatement.xml"
    if mybatis_assistant_verify_inspection_output "${output}" "${evidence}"; then
        echo "额外 MyBatis 问题不应通过检查契约" >&2
        return 1
    fi
    rm -f -- "${output}/MyBatisMissingStatement.xml"

    sed 's/findSummary/findOther/g' "${output}/MyBatisUnusedStatement.xml" \
        > "${output}/MyBatisUnusedStatement.invalid.xml"
    mv "${output}/MyBatisUnusedStatement.invalid.xml" \
        "${output}/MyBatisUnusedStatement.xml"
    if mybatis_assistant_verify_inspection_output "${output}" "${evidence}"; then
        echo "错误 statement 不应通过检查契约" >&2
        return 1
    fi
}

test_versioned_noise_allowlist() {
    local log_261="${TEST_ROOT}/idea-261.log"
    local log_252="${TEST_ROOT}/idea-252.log"
    local allowed="${TEST_ROOT}/allowed.log"
    local unexpected="${TEST_ROOT}/unexpected.log"
    {
        printf '%s\n' '2026-08-12 11:42:53,873 [1] SEVERE - #c.i.c.InspectionsResultUtil - Descriptions are missed for tools: MongoJSResolveInspection, MysqlParsingInspection'
        printf '%s\n' '2026-08-12 11:42:53,875 [2] SEVERE - #c.i.c.InspectionsResultUtil - IntelliJ IDEA 2026.1.4  Build #IU-261.26222.65'
        printf '%s\n' '2026-08-12 11:42:53,875 [2] SEVERE - #c.i.c.InspectionsResultUtil - OS: Linux'
    } > "${log_261}"
    mybatis_assistant_classify_ide_failures \
        "${log_261}" 2026.1.4 "${allowed}" "${unexpected}"
    [[ "$(mybatis_assistant_count_lines "${allowed}")" == "3" ]]
    assert_file_empty "${unexpected}"

    printf '%s\n' '2026-08-12 [1] SEVERE - #c.i.c.InspectionsResultUtil - Descriptions are missed for tools: UnknownInspection' \
        >> "${log_261}"
    mybatis_assistant_classify_ide_failures \
        "${log_261}" 2026.1.4 "${allowed}" "${unexpected}"
    assert_file_contains "${unexpected}" 'UnknownInspection'

    {
        printf '%s\n' '2026-08-12 [1] SEVERE - #c.i.c.InspectionsResultUtil - IntelliJ IDEA 2026.1.4  Build #IU-261.26222.65'
        printf '%s\n' '2026-08-12 [2] SEVERE - #c.i.c.InspectionsResultUtil - OS: Linux'
    } > "${log_261}"
    mybatis_assistant_classify_ide_failures \
        "${log_261}" 2026.1.4 "${allowed}" "${unexpected}"
    assert_file_contains "${unexpected}" 'IntelliJ IDEA 2026.1.4'
    assert_file_contains "${unexpected}" 'OS: Linux'

    {
        printf '%s\n' '2026-08-12 [1] SEVERE - #c.i.d.LoadingState - Should be called at least in the state COMPONENTS_LOADED, the current state is: CONFIGURATION_STORE_INITIALIZED'
        printf '%s\n' '2026-08-12 [2] SEVERE - #c.i.d.LoadingState - IntelliJ IDEA 2025.2.6.2  Build #IU-252.28539.54'
        printf '%s\n' '2026-08-12 [2] SEVERE - #c.i.d.LoadingState - OS: Linux'
    } > "${log_252}"
    mybatis_assistant_classify_ide_failures \
        "${log_252}" 2025.2.6.2 "${allowed}" "${unexpected}"
    [[ "$(mybatis_assistant_count_lines "${allowed}")" == "3" ]]
    assert_file_empty "${unexpected}"

    printf '%s\n' '2026-08-12 [3] ERROR - #io.github.ns3154.mybatisassistant - unexpected' \
        >> "${log_252}"
    printf '%s\n' '2026-08-12 [4] SEVERE - STDOUT - unexpected-without-logger-prefix' \
        >> "${log_252}"
    mybatis_assistant_classify_ide_failures \
        "${log_252}" 2025.2.6.2 "${allowed}" "${unexpected}"
    assert_file_contains "${unexpected}" 'unexpected'
    assert_file_contains "${unexpected}" 'unexpected-without-logger-prefix'
}

test_project_manifest() {
    local repository="${TEST_ROOT}/manifest-repository"
    local baseline="${TEST_ROOT}/manifest-baseline.tsv"
    local current="${TEST_ROOT}/manifest-current.tsv"
    local differences="${TEST_ROOT}/manifest.diff"
    mkdir -p "${repository}/sample/ignored"
    git -C "${repository}" init -q
    printf '%s\n' 'ignored/' > "${repository}/.gitignore"
    printf '%s\n' 'stable' > "${repository}/sample/source.txt"
    printf '%s\n' 'generated-a' > "${repository}/sample/ignored/generated.txt"
    git -C "${repository}" add .gitignore sample/source.txt

    mybatis_assistant_write_project_manifest \
        "${repository}" "${repository}/sample" "${baseline}"
    printf '%s\n' 'generated-b' > "${repository}/sample/ignored/generated.txt"
    mybatis_assistant_write_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    # 此 manifest 的语义仅是版本控制可见文件；ignored 产物由下一项独立契约覆盖。
    mybatis_assistant_verify_project_unchanged \
        "${baseline}" "${current}" "${differences}"

    printf '%s\n' 'changed' > "${repository}/sample/source.txt"
    mybatis_assistant_write_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    if mybatis_assistant_verify_project_unchanged \
        "${baseline}" "${current}" "${differences}"; then
        echo "版本控制可见项目文件变化不应通过" >&2
        return 1
    fi
    assert_file_contains "${differences}" 'source.txt'
}

test_controlled_ignored_project_manifest() {
    local repository="${TEST_ROOT}/ignored-repository"
    local baseline="${TEST_ROOT}/ignored-baseline.tsv"
    local current="${TEST_ROOT}/ignored-current.tsv"
    local delta="${TEST_ROOT}/ignored-delta.tsv"
    mkdir -p "${repository}/sample/.idea" "${repository}/sample/module/target"
    git -C "${repository}" init -q
    {
        printf '%s\n' 'sample/.idea/workspace.xml'
        printf '%s\n' 'sample/**/target/'
        printf '%s\n' 'sample/**/build/'
        printf '%s\n' 'sample/**/.gradle/'
    } > "${repository}/.gitignore"
    printf '%s\n' '<workspace baseline="true"/>' \
        > "${repository}/sample/.idea/workspace.xml"
    printf '%s\n' 'compiled' > "${repository}/sample/module/target/output.bin"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${repository}" "${repository}/sample" "${baseline}"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    mybatis_assistant_verify_controlled_ignored_project_delta \
        "${baseline}" "${current}" "${delta}"

    printf '%s\n' '<workspace changed="true"/>' \
        > "${repository}/sample/.idea/workspace.xml"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    if mybatis_assistant_verify_controlled_ignored_project_delta \
        "${baseline}" "${current}" "${delta}"; then
        echo "ignored 的 .idea 变化不应被漏检" >&2
        return 1
    fi
    assert_file_contains "${delta}" 'sample/.idea/workspace.xml'

    printf '%s\n' 'new output' > "${repository}/sample/module/target/new-output.bin"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    if mybatis_assistant_verify_controlled_ignored_project_delta \
        "${baseline}" "${current}" "${delta}"; then
        echo "ignored 的 target 新增文件不应被漏检" >&2
        return 1
    fi
    assert_file_contains "${delta}" 'module/target/new-output.bin'

    mkdir -p "${repository}/sample/module/build" \
        "${repository}/sample/module/.gradle"
    printf '%s\n' 'new build output' \
        > "${repository}/sample/module/build/new-output.bin"
    printf '%s\n' 'new gradle cache' \
        > "${repository}/sample/module/.gradle/cache.bin"
    mybatis_assistant_write_controlled_ignored_project_manifest \
        "${repository}" "${repository}/sample" "${current}"
    if mybatis_assistant_verify_controlled_ignored_project_delta \
        "${baseline}" "${current}" "${delta}"; then
        echo "空基线后的 build/.gradle 新增文件不应被漏检" >&2
        return 1
    fi
    assert_file_contains "${delta}" 'module/build/new-output.bin'
    assert_file_contains "${delta}" 'module/.gradle/cache.bin'
}

test_process_cleanup_identity() {
    local identity="${TEST_ROOT}/cleanup-processes.tsv"
    local live="${TEST_ROOT}/cleanup-live.tsv"
    local run_pid
    local run_started_at
    local run_command

    /bin/bash -c 'sleep 30' &
    run_pid=$!
    run_started_at="$(mybatis_assistant_process_started_at "${run_pid}")"
    run_command="$(mybatis_assistant_process_command "${run_pid}")"
    printf '%s\t%s\t%s\n' \
        "${run_pid}" "${run_started_at}" "${run_command}" > "${identity}"

    # 错误命令身份不得命中，也不得终止真实进程。
    mybatis_assistant_signal_verified_process \
        TERM "${run_pid}" "${run_started_at}" "${run_command} --wrong"
    mybatis_assistant_process_is_running "${run_pid}"

    mybatis_assistant_terminate_scoped_run \
        "${run_pid}" "${run_started_at}" "${run_command}" '' '' '' "${identity}"
    wait "${run_pid}" 2>/dev/null || true
    if mybatis_assistant_process_is_running "${run_pid}"; then
        echo "精确身份清理后进程不应存活" >&2
        return 1
    fi

    # 活进程清单必须同时匹配 PID、lstart 和完整 command。
    : > "${live}"
    printf '%s\t%s\t%s\n' \
        "$$" "$(mybatis_assistant_process_started_at "$$")" \
        "$(mybatis_assistant_process_command "$$") --wrong" > "${identity}"
    mybatis_assistant_write_live_recorded_processes "${identity}" "${live}"
    assert_file_empty "${live}"
}

test_cleanup_waits_for_recorded_descendant() {
    local identity="${TEST_ROOT}/cleanup-descendant-processes.tsv"
    local run_pid
    local child_pid
    local run_started_at
    local run_command

    /bin/bash -c 'trap "" TERM; sleep 30 & wait' &
    run_pid=$!
    for _ in 1 2 3 4 5; do
        child_pid="$(pgrep -P "${run_pid}" | head -n 1 || true)"
        [[ -n "${child_pid}" ]] && break
        sleep 1
    done
    [[ -n "${child_pid}" ]]
    run_started_at="$(mybatis_assistant_process_started_at "${run_pid}")"
    run_command="$(mybatis_assistant_process_command "${run_pid}")"
    : > "${identity}"
    mybatis_assistant_record_process_tree "${run_pid}" "${identity}"

    mybatis_assistant_terminate_scoped_run \
        "${run_pid}" "${run_started_at}" "${run_command}" '' '' '' "${identity}"
    wait "${run_pid}" 2>/dev/null || true
    if mybatis_assistant_process_is_running "${run_pid}" \
        || mybatis_assistant_process_is_running "${child_pid}"; then
        echo "清理必须等待并终止已记录的 TERM-resistant 进程树" >&2
        return 1
    fi
}

test_process_identity_and_resource_bounds() {
    local identities="${TEST_ROOT}/processes.tsv"
    local live="${TEST_ROOT}/live.tsv"
    local child_pid
    : > "${identities}"
    sleep 2 &
    child_pid=$!
    mybatis_assistant_record_process_tree "${child_pid}" "${identities}"
    if mybatis_assistant_wait_for_recorded_processes_exit "${identities}" "${live}" 1; then
        echo "仍存活的精确 PID 不应被判定为已退出" >&2
        return 1
    fi
    assert_file_contains "${live}" "${child_pid}"
    wait "${child_pid}"
    mybatis_assistant_wait_for_recorded_processes_exit "${identities}" "${live}" 2
    assert_file_empty "${live}"

    mybatis_assistant_resource_trend_is_bounded 100 10 200 20
    if mybatis_assistant_resource_trend_is_bounded \
        0 0 $((MYBATIS_ASSISTANT_MAX_SANDBOX_GROWTH_BYTES + 1)) 0; then
        echo "超限沙箱增长不应通过" >&2
        return 1
    fi
}

test_settings_are_restored() {
    local settings="${TEST_ROOT}/config/options/mybatisAssistant.xml"
    local original="${TEST_ROOT}/original-settings.xml"
    mkdir -p "$(dirname "${settings}")"
    printf '%s\n' '<application><component name="original"/></application>' > "${settings}"
    cp "${settings}" "${original}"
    mybatis_assistant_prepare_lifecycle_settings "${settings}" 39473
    assert_file_contains "${settings}" 'name="mcpEnabled" value="true"'
    assert_file_contains "${settings}" 'name="mcpPort" value="39473"'
    assert_file_contains "${settings}" 'name="mcpWriteToolsEnabled" value="false"'
    mybatis_assistant_restore_lifecycle_settings
    cmp -s "${settings}" "${original}"
}

test_mcp_protocol_fingerprint() {
    local headers="${TEST_ROOT}/mcp-headers.txt"
    local body="${TEST_ROOT}/mcp-body.json"
    {
        printf '%s\r\n' 'HTTP/1.1 401 Unauthorized'
        printf '%s\r\n' 'Cache-Control: no-store'
        printf '%s\r\n' 'Content-Type: application/json; charset=utf-8'
        printf '%s\r\n' 'X-Content-Type-Options: nosniff'
        printf '\r\n'
    } > "${headers}"
    printf '%s\n' '{"error":"unauthorized"}' > "${body}"
    mybatis_assistant_verify_mcp_probe_files 401 "${headers}" "${body}"

    printf '%s\n' '{"error":"not found"}' > "${body}"
    if mybatis_assistant_verify_mcp_probe_files 401 "${headers}" "${body}"; then
        echo "非 MyBatis MCP 指纹响应不应通过" >&2
        return 1
    fi
}

grep -Fq 'class="MyBatisUnusedStatement"' \
    "${TEST_PROJECT_ROOT}/config/lifecycle-inspection-profile.xml"
awk '
    /run_ide_command=\(/ { in_command = 1; next }
    in_command && /--no-daemon/ { no_daemon = 1 }
    in_command && /runIde/ { run_ide = 1; exit }
    END { exit !(no_daemon && run_ide) }
' "${TEST_PROJECT_ROOT}/scripts/verify-sandbox-lifecycle.sh"
awk '
    /run_ide_command\+=\("--args=exit"\)/ { exit_contract = 1 }
    /\.\/gradlew --no-daemon.*runIde --args=exit/ { duplicate_launch = 1 }
    END { exit !(exit_contract && !duplicate_launch) }
' "${TEST_PROJECT_ROOT}/scripts/verify-sandbox-lifecycle.sh"
awk '
    /run-gradle-with-infrastructure-retry\.sh/ { in_prepare = 1; next }
    in_prepare && /--no-daemon/ { no_daemon = 1 }
    in_prepare && /prepareSandbox/ { prepare = 1; exit }
    END { exit !(no_daemon && prepare) }
' "${TEST_PROJECT_ROOT}/scripts/verify-sandbox-lifecycle.sh"
awk '
    /run-gradle-with-infrastructure-retry\.sh/ { in_prepare = 1; next }
    in_prepare && /--no-daemon prepareSandbox/ { found = 1; exit }
    END { exit !found }
' "${TEST_PROJECT_ROOT}/scripts/verify-optional-dependency-isolation.sh"
awk '
    /\.\/gradlew --no-daemon runIde/ { found = 1 }
    END { exit !found }
' "${TEST_PROJECT_ROOT}/scripts/verify-optional-dependency-isolation.sh"
test_inspection_contract
test_versioned_noise_allowlist
test_project_manifest
test_controlled_ignored_project_manifest
test_process_identity_and_resource_bounds
test_process_cleanup_identity
test_cleanup_waits_for_recorded_descendant
test_settings_are_restored
test_mcp_protocol_fingerprint

echo "生命周期交付契约 Shell 测试通过"
