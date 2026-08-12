#!/usr/bin/env bash

set -euo pipefail

readonly TEST_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${TEST_PROJECT_ROOT}/scripts/verify-optional-dependency-isolation.sh"

readonly TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-isolation-contract.XXXXXX")"

cleanup() {
    case "${TEST_ROOT}" in
        "${TMPDIR:-/tmp}"/mybatis-isolation-contract.*) rm -rf -- "${TEST_ROOT}" ;;
        *)
            echo "拒绝清理非测试目录：${TEST_ROOT}" >&2
            return 1
            ;;
    esac
}
trap cleanup EXIT

write_log() {
    local file="$1"
    local loaded="$2"
    local disabled="$3"
    printf '%s\n' \
        "2026-08-12 INFO - #c.i.i.p.PluginManager - Loaded bundled plugins: IDEA CORE (252.28539.54), Java (252.28539.54), ${loaded}" \
        '2026-08-12 INFO - #c.i.i.p.PluginManager - Loaded custom plugins: MyBatis Assistant (0.1.0-SNAPSHOT)' \
        "2026-08-12 INFO - #c.i.i.p.PluginManager - Disabled plugins: ${disabled}" \
        > "${file}"
}

verify_case() {
    local case_name="$1"
    local plugin_names="$2"
    local loaded="$3"
    local disabled="$4"
    local log="${TEST_ROOT}/${case_name}.log"
    local evidence="${TEST_ROOT}/${case_name}.tsv"
    write_log "${log}" "${loaded}" "${disabled}"
    mybatis_assistant_verify_plugin_isolation \
        "${log}" "${plugin_names}" "${evidence}"
    [[ "$(wc -l < "${evidence}" | tr -d '[:space:]')" \
        == "$(( $(tr ',' '\n' <<< "${plugin_names}" | wc -l | tr -d '[:space:]') + 1 ))" ]]
}

[[ "${#MYBATIS_ASSISTANT_ISOLATION_CASE_NAMES[@]}" == "5" ]]
[[ "${#MYBATIS_ASSISTANT_ISOLATION_PLUGIN_IDS[@]}" == "5" ]]
[[ "${#MYBATIS_ASSISTANT_ISOLATION_PLUGIN_NAMES[@]}" == "5" ]]

verify_case kotlin Kotlin \
    'YAML (252.28539.54), Database Tools and SQL (252.28539.54)' \
    'Kotlin (252.28539.54-IJ), JetBrains Ultimate (252.28539.54)'
verify_case spring Spring \
    'Kotlin (252.28539.54-IJ), YAML (252.28539.54)' \
    'Spring (252.28539.54), JetBrains Ultimate (252.28539.54)'
verify_case yaml YAML \
    'Kotlin (252.28539.54-IJ), Spring (252.28539.54)' \
    'YAML (252.28539.54), JetBrains Ultimate (252.28539.54)'
verify_case database 'Database Tools and SQL' \
    'Kotlin (252.28539.54-IJ), Spring (252.28539.54)' \
    'Database Tools and SQL (252.28539.54), JetBrains Ultimate (252.28539.54)'
verify_case all 'Kotlin,Spring,YAML,Database Tools and SQL' \
    'Maven (252.28539.54), Git (252.28539.54)' \
    'Kotlin (252.28539.54-IJ), Spring (252.28539.54), YAML (252.28539.54), Database Tools and SQL (252.28539.54), JetBrains Ultimate (252.28539.54)'

negative_log="${TEST_ROOT}/negative-loaded.log"
write_log "${negative_log}" \
    'Kotlin (252.28539.54-IJ), YAML (252.28539.54)' \
    'Kotlin (252.28539.54-IJ)'
if mybatis_assistant_verify_plugin_isolation \
    "${negative_log}" Kotlin "${TEST_ROOT}/negative-loaded.tsv"; then
    echo "同时出现在加载清单中的目标插件不应通过隔离契约" >&2
    exit 1
fi

negative_log="${TEST_ROOT}/negative-disabled.log"
write_log "${negative_log}" \
    'YAML (252.28539.54), Maven (252.28539.54)' \
    'JetBrains Ultimate (252.28539.54)'
if mybatis_assistant_verify_plugin_isolation \
    "${negative_log}" YAML "${TEST_ROOT}/negative-disabled.tsv"; then
    echo "未被 IDEA 明确报告禁用的目标插件不应通过隔离契约" >&2
    exit 1
fi

echo "可选依赖隔离 Shell 契约测试通过"
