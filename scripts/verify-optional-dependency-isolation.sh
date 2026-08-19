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
# IDEA 2025.2.6.2 在 headless inspect 中会把「显式禁用基础插件后，随之无法
# 加载的 bundled 插件」记录为 SEVERE。这里只登记当前产品包中可由四个目标
# 插件传递关闭的精确插件 ID；脚本仍会从 IDEA 自己输出的依赖错误逐条核对，
# 目标插件、依赖关系、插件名或正文任一变化都会 fail closed。
readonly MYBATIS_ASSISTANT_ISOLATION_DEPENDENT_PLUGIN_EDGES="|com.android.tools.gradle.dcl>org.jetbrains.kotlin|com.intellij.compose>org.jetbrains.kotlin|intellij.ktor>org.jetbrains.kotlin|org.jetbrains.plugins.kotlin.jupyter>org.jetbrains.kotlin|com.intellij.spring.boot>com.intellij.spring|com.intellij.spring.data>com.intellij.spring|com.intellij.spring.integration>com.intellij.spring|com.intellij.spring.messaging>com.intellij.spring|com.intellij.spring.mvc>com.intellij.spring|com.intellij.spring.security>com.intellij.spring|com.intellij.openRewrite>org.jetbrains.plugins.yaml|com.intellij.swagger>org.jetbrains.plugins.yaml|com.intellij.hibernate>com.intellij.database|com.intellij.javaee.app.servers.integration>com.intellij.database|com.intellij.javaee.jakarta.data>com.intellij.database|com.intellij.javaee.jpa>com.intellij.database|com.intellij.persistence>com.intellij.database|com.intellij.javaee.reverseEngineering>com.intellij.database|"
readonly MYBATIS_ASSISTANT_ISOLATION_EXPECTED_INSPECTION="MyBatisUnusedStatement"
readonly MYBATIS_ASSISTANT_ISOLATION_EXPECTED_NAMESPACE="io.github.mybatisideaassistant.lifecycle.UserMapper"
readonly MYBATIS_ASSISTANT_ISOLATION_EXPECTED_STATEMENT="findSummary"
readonly MYBATIS_ASSISTANT_ISOLATION_EXPECTED_SOURCE="src/main/resources/mappers/UserMapper.xml"

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

mybatis_assistant_isolation_list_contains() {
    local comma_separated="$1"
    local expected="$2"
    local remaining="${comma_separated}"
    local candidate

    while [[ -n "${remaining}" ]]; do
        if [[ "${remaining}" == *,* ]]; then
            candidate="${remaining%%,*}"
            remaining="${remaining#*,}"
        else
            candidate="${remaining}"
            remaining=""
        fi
        [[ "${candidate}" == "${expected}" ]] && return 0
    done
    return 1
}

mybatis_assistant_isolation_dependency_error_is_known() {
    local plugin_id="$1"
    local dependency_id="$2"
    local disabled_plugin_ids="$3"

    mybatis_assistant_isolation_list_contains \
        "${disabled_plugin_ids}" "${dependency_id}" || return 1
    case "${MYBATIS_ASSISTANT_ISOLATION_DEPENDENT_PLUGIN_EDGES}" in
        *"|${plugin_id}>${dependency_id}|"*) return 0 ;;
        *) return 1 ;;
    esac
}

mybatis_assistant_isolation_plugin_name_is_known() {
    local plugin_id="$1"
    local plugin_name="$2"

    case "${plugin_id}" in
        com.android.tools.gradle.dcl)
            [[ "${plugin_name}" == "Gradle Declarative Support" ]]
            ;;
        com.intellij.compose)
            [[ "${plugin_name}" == "Compose Multiplatform" ]]
            ;;
        intellij.ktor)
            [[ "${plugin_name}" == "Ktor" ]]
            ;;
        org.jetbrains.plugins.kotlin.jupyter)
            [[ "${plugin_name}" == "Kotlin Notebook" ]]
            ;;
        com.intellij.spring.boot)
            [[ "${plugin_name}" == "Spring Boot" ]]
            ;;
        com.intellij.spring.data)
            [[ "${plugin_name}" == "Spring Data" ]]
            ;;
        com.intellij.spring.integration)
            [[ "${plugin_name}" == "Spring Integration Patterns" ]]
            ;;
        com.intellij.spring.messaging)
            [[ "${plugin_name}" == "Spring Messaging" ]]
            ;;
        com.intellij.spring.mvc)
            [[ "${plugin_name}" == "Spring Web" ]]
            ;;
        com.intellij.spring.security)
            [[ "${plugin_name}" == "Spring Security" ]]
            ;;
        com.intellij.openRewrite)
            [[ "${plugin_name}" == "OpenRewrite" ]]
            ;;
        com.intellij.swagger)
            [[ "${plugin_name}" == "OpenAPI Specifications" ]]
            ;;
        com.intellij.kubernetes)
            [[ "${plugin_name}" == "Kubernetes" ]]
            ;;
        com.intellij.hibernate)
            [[ "${plugin_name}" == "Hibernate" ]]
            ;;
        com.intellij.javaee.app.servers.integration)
            [[ "${plugin_name}" == "Jakarta EE: Application Servers" ]]
            ;;
        com.intellij.javaee.jpa)
            [[ "${plugin_name}" == "Jakarta EE: Persistence (JPA)" ]]
            ;;
        com.intellij.persistence)
            [[ "${plugin_name}" == "JVM Persistence Frameworks" ]]
            ;;
        com.intellij.javaee.jakarta.data)
            [[ "${plugin_name}" == "Jakarta EE: Data" ]]
            ;;
        com.intellij.javaee.reverseEngineering)
            [[ "${plugin_name}" == "Reverse Engineering" ]]
            ;;
        *) return 1 ;;
    esac
}

mybatis_assistant_isolation_missing_dependency_error_is_known() {
    local plugin_id="$1"
    local plugin_name="$2"
    local missing_dependency_id="$3"
    local disabled_plugin_ids="$4"

    [[ "${plugin_id}" == "com.intellij.kubernetes" \
        && "${plugin_name}" == "Kubernetes" ]] || return 1
    [[ "${missing_dependency_id}" == "org.jetbrains.plugins.yaml" ]] || return 1
    mybatis_assistant_isolation_list_contains \
        "${disabled_plugin_ids}" "${missing_dependency_id}"
}

mybatis_assistant_isolation_transitive_error_is_known() {
    local plugin_id="$1"
    local plugin_name="$2"
    local parent_plugin_name="$3"
    local verified_parent_plugins="$4"

    case "${verified_parent_plugins}" in
        *"|${parent_plugin_name}|"*) ;;
        *) return 1 ;;
    esac
    case "${plugin_id}>${parent_plugin_name}" in
        "com.intellij.spring.modulith>Spring Boot")
            [[ "${plugin_name}" == "Spring Modulith" ]]
            ;;
        "com.intellij.spring.cloud>Spring Boot")
            [[ "${plugin_name}" == "Spring Cloud" ]]
            ;;
        "com.intellij.micronaut>JVM Persistence Frameworks")
            [[ "${plugin_name}" == "Micronaut" ]]
            ;;
        "com.intellij.quarkus>JVM Persistence Frameworks")
            [[ "${plugin_name}" == "Quarkus" ]]
            ;;
        "JBoss>Jakarta EE: Application Servers")
            [[ "${plugin_name}" == "WildFly" ]]
            ;;
        "Tomcat>Jakarta EE: Application Servers")
            [[ "${plugin_name}" == "Tomcat and TomEE" ]]
            ;;
        *) return 1 ;;
    esac
}

mybatis_assistant_classify_isolation_failures() {
    local idea_log="$1"
    local platform_version="$2"
    local disabled_plugin_ids="$3"
    local allowlisted_output="$4"
    local unexpected_output="$5"
    local base_allowlisted
    local base_unexpected
    local plugin_allowlisted
    local line
    local active_plugin_error=0
    local plugin_context_remaining=0
    local saw_dependency=0
    local verified_plugin_error_count=0
    local saw_build_context=0
    local saw_jdk_context=0
    local saw_os_context=0
    local verified_parent_plugins="|"
    local plugin_name=""
    local plugin_id=""
    local dependency_id=""
    local message_regex="^  Plugin '([^']+)' \(([^)]+)\) requires plugin with id=([^[:space:]]+) to be enabled$"
    local transitive_regex="^  Plugin '([^']+)' \(([^)]+)\) has dependency on '([^']+)' which cannot be loaded$"
    local missing_dependency_regex="^  Plugin '([^']+)' \(([^)]+)\) has dependency on '([^']+)' which is not installed$"
    local plugin_build_context_regex=' SEVERE - #c\.i\.i\.p\.PluginManager - IntelliJ IDEA 2025\.2\.6\.2  Build #IU-252\.28539\.54$'
    local plugin_jdk_context_regex=' SEVERE - #c\.i\.i\.p\.PluginManager - JDK: 21\.0\.9; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s\.r\.o\.$'
    local plugin_os_context_regex=' SEVERE - #c\.i\.i\.p\.PluginManager - OS: (Linux|Mac OS X)$'
    local plugin_last_action_context_regex=' SEVERE - #c\.i\.i\.p\.PluginManager - Last Action: ?$'

    base_allowlisted="${allowlisted_output}.base"
    base_unexpected="${unexpected_output}.base"
    plugin_allowlisted="${allowlisted_output}.plugin"
    : > "${allowlisted_output}"
    : > "${unexpected_output}"
    : > "${plugin_allowlisted}"
    mybatis_assistant_classify_ide_failures \
        "${idea_log}" "${platform_version}" \
        "${base_allowlisted}" "${base_unexpected}"
    if [[ -s "${base_allowlisted}" ]]; then
        cp "${base_allowlisted}" "${allowlisted_output}"
    fi

    # 常规分类器只输出 SEVERE/ERROR 头。隔离场景唯一额外允许的是 PluginManager
    # 精确依赖错误；其完整正文必须逐项证明依赖了本场景明确禁用的目标插件。
    while IFS= read -r line; do
        if [[ "${line}" == *" SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:" ]]; then
            if (( active_plugin_error == 1 || plugin_context_remaining > 0 \
                || verified_plugin_error_count > 0 )); then
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            fi
            active_plugin_error=1
            plugin_context_remaining=128
            saw_dependency=0
            saw_build_context=0
            saw_jdk_context=0
            saw_os_context=0
            verified_parent_plugins="|"
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
            continue
        fi
        if (( plugin_context_remaining > 0 )); then
            plugin_context_remaining=$((plugin_context_remaining - 1))
        fi
        if (( active_plugin_error == 1 )) && [[ "${line}" =~ ${message_regex} ]]; then
            plugin_name="${BASH_REMATCH[1]}"
            plugin_id="${BASH_REMATCH[2]}"
            dependency_id="${BASH_REMATCH[3]}"
            if ! mybatis_assistant_isolation_plugin_name_is_known \
                "${plugin_id}" "${plugin_name}" \
                || ! mybatis_assistant_isolation_dependency_error_is_known \
                "${plugin_id}" "${dependency_id}" "${disabled_plugin_ids}"; then
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            fi
            saw_dependency=1
            case "${plugin_id}" in
                com.intellij.spring.boot)
                    verified_parent_plugins="${verified_parent_plugins}Spring Boot|"
                    ;;
                com.intellij.persistence)
                    verified_parent_plugins="${verified_parent_plugins}JVM Persistence Frameworks|"
                    ;;
                com.intellij.javaee.app.servers.integration)
                    verified_parent_plugins="${verified_parent_plugins}Jakarta EE: Application Servers|"
                    ;;
            esac
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
            continue
        fi
        if (( active_plugin_error == 1 )) && [[ "${line}" =~ ${transitive_regex} ]]; then
            plugin_name="${BASH_REMATCH[1]}"
            plugin_id="${BASH_REMATCH[2]}"
            if ! mybatis_assistant_isolation_transitive_error_is_known \
                "${plugin_id}" "${plugin_name}" "${BASH_REMATCH[3]}" \
                "${verified_parent_plugins}"; then
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            fi
            saw_dependency=1
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
            continue
        fi
        if (( active_plugin_error == 1 )) \
            && [[ "${line}" =~ ${missing_dependency_regex} ]]; then
            plugin_name="${BASH_REMATCH[1]}"
            plugin_id="${BASH_REMATCH[2]}"
            dependency_id="${BASH_REMATCH[3]}"
            if ! mybatis_assistant_isolation_missing_dependency_error_is_known \
                "${plugin_id}" "${plugin_name}" "${dependency_id}" \
                "${disabled_plugin_ids}"; then
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            fi
            saw_dependency=1
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
            continue
        fi
        if (( active_plugin_error == 1 )) \
            && [[ "${line}" == 'java.lang.Throwable: Problems found loading plugins:' ]]; then
            (( saw_dependency == 1 )) || {
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            }
            active_plugin_error=0
            verified_plugin_error_count=$((verified_plugin_error_count + 1))
            continue
        fi
        if (( active_plugin_error == 1 )); then
            printf '%s\n' "${line}" >> "${unexpected_output}"
            return 1
        fi
        if (( verified_plugin_error_count == 1 && plugin_context_remaining > 0 )) \
            && [[ "${line}" =~ ${plugin_build_context_regex} ]]; then
            (( saw_build_context == 0 )) || {
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            }
            saw_build_context=1
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
        elif (( verified_plugin_error_count == 1 && plugin_context_remaining > 0 )) \
            && [[ "${line}" =~ ${plugin_jdk_context_regex} ]]; then
            (( saw_jdk_context == 0 )) || {
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            }
            saw_jdk_context=1
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
        elif (( verified_plugin_error_count == 1 && plugin_context_remaining > 0 )) \
            && [[ "${line}" =~ ${plugin_os_context_regex} ]]; then
            (( saw_os_context == 0 )) || {
                printf '%s\n' "${line}" >> "${unexpected_output}"
                return 1
            }
            saw_os_context=1
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
        elif (( verified_plugin_error_count == 1 && plugin_context_remaining > 0 )) \
            && [[ "${line}" =~ ${plugin_last_action_context_regex} ]]; then
            printf '%s\n' "${line}" >> "${plugin_allowlisted}"
        elif [[ "${line}" == *" SEVERE -"* ]]; then
            plugin_context_remaining=0
        fi
    done < "${idea_log}"
    if (( active_plugin_error != 0 )); then
        printf '%s\n' 'PluginManager 依赖错误块在 Throwable 结束标记前被截断' \
            >> "${unexpected_output}"
        return 1
    fi
    if (( verified_plugin_error_count > 0 \
        && (saw_build_context != 1 || saw_jdk_context != 1 \
            || saw_os_context != 1) )); then
        printf 'PluginManager 依赖错误块缺少精确 Build/JDK/OS 上下文：build=%s jdk=%s os=%s\n' \
            "${saw_build_context}" "${saw_jdk_context}" "${saw_os_context}" \
            >> "${unexpected_output}"
        return 1
    fi

    while IFS= read -r line; do
        [[ -n "${line}" ]] || continue
        if grep -Fxq -- "${line}" "${plugin_allowlisted}"; then
            printf '%s\n' "${line}" >> "${allowlisted_output}"
        else
            printf '%s\n' "${line}" >> "${unexpected_output}"
        fi
    done < "${base_unexpected}"
    while IFS= read -r line; do
        [[ -n "${line}" ]] || continue
        grep -Fxq -- "${line}" "${allowlisted_output}" \
            || printf '%s\n' "${line}" >> "${allowlisted_output}"
    done < "${plugin_allowlisted}"
    [[ ! -s "${unexpected_output}" ]]
}

mybatis_assistant_verify_isolation_inspection_output() {
    local inspection_output="$1"
    local evidence_file="$2"
    local expected_file="${inspection_output}/${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_INSPECTION}.xml"
    local descriptions_file="${inspection_output}/.descriptions.xml"
    local inspection_file
    local problem_block
    local normalized_problem_block
    local description_match_count
    local expected_problem_count
    local total_problem_count=0

    [[ -s "${descriptions_file}" && -s "${expected_file}" ]] || return 1
    description_match_count="$({
        grep -oF -- \
            "<inspection shortName=\"${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_INSPECTION}\"" \
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
        "<file>file://\$PROJECT_DIR\$/${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_SOURCE}</file>" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" \
        | grep -Eq '^<line>[1-9][0-9]*</line>$' || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Eq -- \
        "^<problem_class id=\"${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_INSPECTION}\"([[:space:]][^>]*)?>statement 未找到 Mapper 方法</problem_class>$" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Fxq -- \
        "<description>未找到对应的 Java Mapper 方法：${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_NAMESPACE}.${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_STATEMENT}</description>" \
        || return 1
    printf '%s\n' "${normalized_problem_block}" | grep -Fxq -- \
        "<highlighted_element>&quot;${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_STATEMENT}&quot;</highlighted_element>" \
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
        '2026-08-13.v1' \
        "${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_INSPECTION}" \
        "${expected_problem_count}" "${total_problem_count}" \
        "${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_SOURCE}" \
        "${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_NAMESPACE}" \
        "${MYBATIS_ASSISTANT_ISOLATION_EXPECTED_STATEMENT}" >> "${evidence_file}"
    cp "${descriptions_file}" "${evidence_file%.tsv}-descriptions.xml"
}

mybatis_assistant_verify_inspect_progress() {
    local process_output="$1"
    local inspection_profile="$2"
    local expected_profile_name="$3"
    local inspection_output="$4"
    local evidence_file="${5:-}"

    [[ -s "${process_output}" ]] || return 1
    grep -Fq -- "Opening project" "${process_output}" || return 1
    grep -Fq -- "Loaded the '${expected_profile_name}' profile from the file '${inspection_profile}'" \
        "${process_output}" || return 1
    grep -Fq -- "Inspecting with the '${expected_profile_name}' profile" \
        "${process_output}" || return 1
    grep -Fq -- 'Scanning scope' "${process_output}" || return 1
    awk '
        index($0, "Analyzing code in ") > 0 \
            && index($0, "/src/main/java/io/github/mybatisideaassistant/lifecycle/UserMapper.java") > 0 {
            found = 1
        }
        END { exit !found }
    ' "${process_output}" || return 1
    awk '
        index($0, "Analyzing code in ") > 0 \
            && index($0, "/src/main/resources/mappers/UserMapper.xml") > 0 {
            found = 1
        }
        END { exit !found }
    ' "${process_output}" || return 1
    grep -Fxq -- 'Done.' "${process_output}" || return 1
    find "${inspection_output}" -maxdepth 1 -type f -name '*.xml' \
        -print -quit | grep -q . || return 1
    if [[ -n "${evidence_file}" ]]; then
        printf 'contract_version\topening_project\tprofile_loaded\tinspection_started\tscanning_scope\tjava_mapper_analyzed\txml_mapper_analyzed\tdone\tresult_xml_present\n' \
            > "${evidence_file}"
        printf '%s\t1\t1\t1\t1\t1\t1\t1\t1\n' \
            '2026-08-13.v1' >> "${evidence_file}"
    fi
}

mybatis_assistant_capture_final_idea_log_after_process_exit() {
    local ide_pid="$1"
    local ide_started_at="$2"
    local ide_command="$3"
    local process_identity_file="$4"
    local live_process_file="$5"
    local sandbox_log="$6"
    local case_idea_log="$7"
    local timeout_seconds="$8"

    # Gradle wrapper 退出并不等于 IDEA 及其子进程已经停止写日志。先等待本轮
    # 记录的全部精确进程身份退出，再单独复核 IDEA 身份，最后才冻结日志。
    mybatis_assistant_wait_for_recorded_processes_exit \
        "${process_identity_file}" "${live_process_file}" \
        "${timeout_seconds}" || return 1
    if mybatis_assistant_process_exact_identity_matches \
        "${ide_pid}" "${ide_started_at}" "${ide_command}"; then
        return 1
    fi
    [[ -f "${sandbox_log}" ]] || return 1
    cp "${sandbox_log}" "${case_idea_log}"
}

mybatis_assistant_verify_final_shutdown_log() {
    local case_idea_log="$1"
    local evidence_file="$2"
    local marker_lines
    local project_dispose_line
    local ide_shutdown_line

    marker_lines="$(awk '
        $0 ~ / INFO - #c\.i\.p\.p\.ProjectEntitiesStorage - Project ProjectId\(id=[[:alnum:]]+\) is disposed, removing entity$/ {
            project_dispose_count++
            project_dispose_line = NR
        }
        BEGIN {
            shutdown_marker = " INFO - #c.i.p.i.b.AppStarter - ------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------"
        }
        length($0) >= length(shutdown_marker) \
            && substr($0, length($0) - length(shutdown_marker) + 1) == shutdown_marker {
            ide_shutdown_count++
            ide_shutdown_line = NR
        }
        END {
            if (project_dispose_count != 1 || ide_shutdown_count != 1 \
                || project_dispose_line >= ide_shutdown_line) exit 1
            print project_dispose_line "\t" ide_shutdown_line
        }
    ' "${case_idea_log}")" || return 1
    IFS=$'\t' read -r project_dispose_line ide_shutdown_line \
        <<< "${marker_lines}"
    printf 'contract_version\tproject_dispose_logged\tide_shutdown_logged\tproject_dispose_line\tide_shutdown_line\n' \
        > "${evidence_file}"
    printf '%s\t1\t1\t%s\t%s\n' \
        '2026-08-13.v1' "${project_dispose_line}" "${ide_shutdown_line}" \
        >> "${evidence_file}"
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
    local inspection_profile="${project_root}/config/optional-dependency-isolation-profile.xml"
    local semantic_project="${project_root}/samples/lifecycle-inspection-corpus"
    local inspection_profile_name="MyBatis Assistant 可选依赖隔离验收"
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
    printf 'case\tplatform_version\tnoise_allowlist\tdisabled_plugin_ids\tdisabled_state_verified\tplugin_loaded\tproject_opened\tindex_scan_completed\tproject_disposed\tinspection_verified\tinspection_problem_count\tversion_control_visible_files_unchanged\tcontrolled_ignored_artifacts_unchanged\tmax_ide_tree_process_count\torphan_process_count\tsandbox_bytes\tsandbox_files\tsandbox_growth_bytes\tsandbox_growth_files\tallowlisted_error_count\tclassification_verified\tunexpected_error_count\tplugin_error_count\texit_code\n' \
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
        local inspect_progress_evidence="${report_dir}/${case_name}-inspect-progress.tsv"
        local disposal_evidence="${report_dir}/${case_name}-process-teardown.tsv"
        local shutdown_evidence="${report_dir}/${case_name}-shutdown-contract.tsv"
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
        local ide_process_exited=0
        local recorded_processes_exited=0
        local project_dispose_logged=0
        local ide_shutdown_logged=0
        local sandbox_bytes
        local sandbox_files
        local sandbox_growth_bytes
        local sandbox_growth_files
        local allowlisted_error_count
        local classification_verified=0
        local unexpected_error_count
        local plugin_error_count

        rm -f -- \
            "${case_idea_log}" "${plugin_evidence}" "${inspection_evidence}" \
            "${inspection_evidence%.tsv}-descriptions.xml" \
            "${inspect_progress_evidence}" "${disposal_evidence}" \
            "${shutdown_evidence}" \
            "${current_project_manifest}" "${project_diff}" \
            "${current_ignored_project_manifest}" "${ignored_project_delta}" \
            "${allowlisted_errors}" "${allowlisted_errors}.base" \
            "${allowlisted_errors}.plugin" "${unexpected_errors}" \
            "${unexpected_errors}.base"

        printf '%s' "${plugin_ids}" | tr ',' '\n' > "${requested_plugin_file}"
        cp "${requested_plugin_file}" "${disabled_plugins_file}"
        : > "${sandbox_log}"
        : > "${case_output}"
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

        # 2025.2 在 macOS 无界面启动早期会在 Registry 尚未完成加载时读取
        # use.eel.file.watcher，并把平台自身错误记录为 SEVERE。平台日志明确要求
        # 该阶段使用同名系统属性；这里固定为 false，不影响本插件隔离语义。
        JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-Duse.eel.file.watcher=false" \
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
            "Loaded custom plugins: MyBatis Assistant" "${start_line}" 90; then
            echo "可选依赖隔离场景 ${case_name} 未在 90 秒内加载插件" >&2
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

        if mybatis_assistant_capture_final_idea_log_after_process_exit \
            "${ide_pid}" "${ide_started_at}" "${ide_command}" \
            "${process_identity_file}" "${live_process_file}" \
            "${sandbox_log}" "${case_idea_log}" 30; then
            recorded_processes_exited=1
            ide_process_exited=1
        else
            orphan_process_count="$(mybatis_assistant_count_lines \
                "${live_process_file}")"
            echo "可选依赖隔离场景 ${case_name} 的 IDEA 或记录子进程未在日志冻结前退出" >&2
            return 1
        fi

        # 每个场景启动前已清空本沙箱日志；这里只使用 IDEA 与记录子进程精确
        # 退出后的冻结副本。2025.2 的 headless InspectionApplication 不保证
        # 输出普通 UI 的 open/scan marker；资源释放则由最终日志中精确的项目
        # dispose、IDE SHUTDOWN 与进程身份三组证据共同判定。

        if mybatis_assistant_verify_final_shutdown_log \
            "${case_idea_log}" "${shutdown_evidence}"; then
            project_dispose_logged=1
            ide_shutdown_logged=1
        fi

        if cmp -s "${requested_plugin_file}" "${disabled_plugins_file}" \
            && mybatis_assistant_verify_plugin_isolation \
                "${case_idea_log}" "${plugin_names}" "${plugin_evidence}"; then
            disabled_state_verified=1
        fi
        grep -Fq -- "Loaded custom plugins: MyBatis Assistant" "${case_idea_log}" \
            && plugin_loaded=1
        if mybatis_assistant_verify_inspect_progress \
            "${case_output}" "${inspection_profile}" \
            "${inspection_profile_name}" "${inspection_output}" \
            "${inspect_progress_evidence}"; then
            # 2025.2 的 InspectionApplication 不稳定输出普通 UI 的 project-open /
            # Scanning completed 日志；进程标准输出中的打开、profile、扫描、分析、
            # Done 与 XML 产物共同构成真实 inspect 证据。
            project_opened=1
            index_scan_completed=1
        fi
        if mybatis_assistant_verify_isolation_inspection_output \
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
        if (( run_exit_code == 0 && project_opened == 1 \
            && index_scan_completed == 1 && inspection_verified == 1 \
            && project_dispose_logged == 1 && ide_shutdown_logged == 1 \
            && ide_process_exited == 1 && recorded_processes_exited == 1 )); then
            project_disposed=1
        fi
        printf 'contract_version\trun_exit_code\tproject_dispose_logged\tide_shutdown_logged\tide_process_exited\trecorded_processes_exited\tproject_disposed\n' \
            > "${disposal_evidence}"
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            '2026-08-13.v1' "${run_exit_code}" \
            "${project_dispose_logged}" "${ide_shutdown_logged}" \
            "${ide_process_exited}" "${recorded_processes_exited}" \
            "${project_disposed}" >> "${disposal_evidence}"

        if mybatis_assistant_classify_isolation_failures \
            "${case_idea_log}" "${platform_version}" "${plugin_ids}" \
            "${allowlisted_errors}" "${unexpected_errors}"; then
            classification_verified=1
        fi
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

        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "${case_name}" "${platform_version}" \
            "${MYBATIS_ASSISTANT_IDE_NOISE_ALLOWLIST_VERSION}" "${plugin_ids}" \
            "${disabled_state_verified}" "${plugin_loaded}" "${project_opened}" \
            "${index_scan_completed}" "${project_disposed}" \
            "${inspection_verified}" "${inspection_problem_count}" \
            "${project_unchanged}" "${controlled_ignored_artifacts_unchanged}" \
            "${max_process_count}" "${orphan_process_count}" \
            "${sandbox_bytes}" "${sandbox_files}" "${sandbox_growth_bytes}" \
            "${sandbox_growth_files}" "${allowlisted_error_count}" \
            "${classification_verified}" "${unexpected_error_count}" \
            "${plugin_error_count}" "${run_exit_code}" \
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
            || classification_verified != 1 \
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
