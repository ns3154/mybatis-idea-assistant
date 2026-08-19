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

append_plugin_manager_context() {
    local file="$1"
    local os_name="${2:-Linux}"
    printf '%s\n' \
        '2026-08-13 SEVERE - #c.i.i.p.PluginManager - IntelliJ IDEA 2025.2.6.2  Build #IU-252.28539.54' \
        '2026-08-13 SEVERE - #c.i.i.p.PluginManager - JDK: 21.0.9; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o.' \
        "2026-08-13 SEVERE - #c.i.i.p.PluginManager - OS: ${os_name}" \
        >> "${file}"
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

verify_isolation_failure_classifier() {
    local log="${TEST_ROOT}/classifier.log"
    local allowlisted="${TEST_ROOT}/classifier-allowlisted.log"
    local unexpected="${TEST_ROOT}/classifier-unexpected.log"
    {
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:'
        printf '%s\n' "  Plugin 'Gradle Declarative Support' (com.android.tools.gradle.dcl) requires plugin with id=org.jetbrains.kotlin to be enabled"
        printf '%s\n' "  Plugin 'Compose Multiplatform' (com.intellij.compose) requires plugin with id=org.jetbrains.kotlin to be enabled"
        printf '%s\n' 'java.lang.Throwable: Problems found loading plugins:'
        printf '%s\n' '    at com.intellij.ide.plugins.PluginManagerCore.initializePlugins(PluginManagerCore.kt:561)'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - IntelliJ IDEA 2025.2.6.2  Build #IU-252.28539.54'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - JDK: 21.0.9; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o.'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - OS: Linux'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Last Action: '
    } > "${log}"
    mybatis_assistant_classify_isolation_failures \
        "${log}" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"
    [[ -s "${allowlisted}" && ! -s "${unexpected}" ]]

    sed 's/OS: Linux/OS: Mac OS X/' "${log}" > "${log}.mac-os"
    mybatis_assistant_classify_isolation_failures \
        "${log}.mac-os" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"
    [[ -s "${allowlisted}" && ! -s "${unexpected}" ]]

    sed 's/java.lang.Throwable: Problems found loading plugins:/java.lang.Throwable: 无关异常/' \
        "${log}" > "${log}.wrong-terminator"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.wrong-terminator" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "非精确 Throwable 结束行不应通过隔离噪声契约" >&2
        return 1
    fi

    local context_name
    local context_pattern
    for context_name in build jdk os; do
        case "${context_name}" in
            build) context_pattern='IntelliJ IDEA 2025.2.6.2  Build' ;;
            jdk) context_pattern='PluginManager - JDK:' ;;
            os) context_pattern='PluginManager - OS:' ;;
        esac
        grep -Fv -- "${context_pattern}" "${log}" \
            > "${log}.missing-${context_name}"
        if mybatis_assistant_classify_isolation_failures \
            "${log}.missing-${context_name}" 2025.2.6.2 org.jetbrains.kotlin \
            "${allowlisted}" "${unexpected}"; then
            echo "缺少 ${context_name} 上下文不应通过隔离噪声契约" >&2
            return 1
        fi
    done

    sed 's/OS: Linux/OS: 未登记系统/' "${log}" > "${log}.unknown-os"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.unknown-os" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "未登记操作系统不应通过隔离噪声契约" >&2
        return 1
    fi

    sed 's/com.intellij.compose/com.intellij.unknown/' "${log}" > "${log}.unknown"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.unknown" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "未知 bundled 插件不应通过隔离噪声契约" >&2
        return 1
    fi

    sed 's/Compose Multiplatform/伪造显示名/' "${log}" > "${log}.wrong-name"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.wrong-name" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "bundled 插件显示名变化不应通过隔离噪声契约" >&2
        return 1
    fi

    sed "/Compose Multiplatform/a\\
  未登记的依赖错误正文" "${log}" > "${log}.unknown-body"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.unknown-body" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "未登记的依赖错误正文不应被隔离噪声契约忽略" >&2
        return 1
    fi

    sed 's/id=org.jetbrains.kotlin/id=com.intellij.spring/g' \
        "${log}" > "${log}.wrong-dependency"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.wrong-dependency" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "非本场景禁用目标不应通过隔离噪声契约" >&2
        return 1
    fi

    {
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:'
        printf '%s\n' "  Plugin 'Spring Boot' (com.intellij.spring.boot) requires plugin with id=com.intellij.spring to be enabled"
        printf '%s\n' "  Plugin 'Spring Integration Patterns' (com.intellij.spring.integration) requires plugin with id=com.intellij.spring to be enabled"
        printf '%s\n' "  Plugin 'Spring Web' (com.intellij.spring.mvc) requires plugin with id=com.intellij.spring to be enabled"
        printf '%s\n' "  Plugin 'Spring Modulith' (com.intellij.spring.modulith) has dependency on 'Spring Boot' which cannot be loaded"
        printf '%s\n' "  Plugin 'Spring Cloud' (com.intellij.spring.cloud) has dependency on 'Spring Boot' which cannot be loaded"
        printf '%s\n' 'java.lang.Throwable: Problems found loading plugins:'
    } > "${log}.spring"
    append_plugin_manager_context "${log}.spring"
    mybatis_assistant_classify_isolation_failures \
        "${log}.spring" 2025.2.6.2 com.intellij.spring \
        "${allowlisted}" "${unexpected}"
    [[ -s "${allowlisted}" && ! -s "${unexpected}" ]]

    sed "/Spring Boot.*requires plugin/d" \
        "${log}.spring" > "${log}.spring-without-parent"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.spring-without-parent" 2025.2.6.2 com.intellij.spring \
        "${allowlisted}" "${unexpected}"; then
        echo "缺少已验证 Spring Boot 直接依赖时不得放行传递错误" >&2
        return 1
    fi

    sed "s/dependency on 'Spring Boot'/dependency on '伪造父插件'/" \
        "${log}.spring" > "${log}.spring-wrong-parent"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.spring-wrong-parent" 2025.2.6.2 com.intellij.spring \
        "${allowlisted}" "${unexpected}"; then
        echo "错误传递父插件不得通过隔离噪声契约" >&2
        return 1
    fi

    sed '/java.lang.Throwable:/,$d' "${log}" > "${log}.truncated"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.truncated" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "截断的 PluginManager 错误块不得通过隔离噪声契约" >&2
        return 1
    fi
    [[ -s "${unexpected}" ]] || {
        echo "分类失败必须留下非空错误证据" >&2
        return 1
    }

    {
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:'
        printf '%s\n' "  Plugin 'OpenRewrite' (com.intellij.openRewrite) requires plugin with id=org.jetbrains.plugins.yaml to be enabled"
        printf '%s\n' "  Plugin 'OpenAPI Specifications' (com.intellij.swagger) requires plugin with id=org.jetbrains.plugins.yaml to be enabled"
        printf '%s\n' "  Plugin 'Kubernetes' (com.intellij.kubernetes) has dependency on 'org.jetbrains.plugins.yaml' which is not installed"
        printf '%s\n' 'java.lang.Throwable: Problems found loading plugins:'
    } > "${log}.yaml"
    append_plugin_manager_context "${log}.yaml"
    mybatis_assistant_classify_isolation_failures \
        "${log}.yaml" 2025.2.6.2 org.jetbrains.plugins.yaml \
        "${allowlisted}" "${unexpected}"
    [[ -s "${allowlisted}" && ! -s "${unexpected}" ]]

    sed "s/dependency on 'org.jetbrains.plugins.yaml'/dependency on '伪造依赖'/" \
        "${log}.yaml" > "${log}.yaml-wrong-dependency"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.yaml-wrong-dependency" 2025.2.6.2 org.jetbrains.plugins.yaml \
        "${allowlisted}" "${unexpected}"; then
        echo "YAML 传递错误的依赖 ID 不匹配时不得放行" >&2
        return 1
    fi

    sed "/Plugin 'Kubernetes'/s/org.jetbrains.plugins.yaml/com.intellij.database/" \
        "${log}.yaml" > "${log}.yaml-kubernetes-database"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.yaml-kubernetes-database" 2025.2.6.2 \
        org.jetbrains.plugins.yaml,com.intellij.database \
        "${allowlisted}" "${unexpected}"; then
        echo "Kubernetes 缺失依赖即使也被禁用，也只能精确为 YAML" >&2
        return 1
    fi

    {
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:'
        printf '%s\n' "  Plugin 'Hibernate' (com.intellij.hibernate) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'Jakarta EE: Application Servers' (com.intellij.javaee.app.servers.integration) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'Jakarta EE: Data' (com.intellij.javaee.jakarta.data) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'Jakarta EE: Persistence (JPA)' (com.intellij.javaee.jpa) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'JVM Persistence Frameworks' (com.intellij.persistence) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'Reverse Engineering' (com.intellij.javaee.reverseEngineering) requires plugin with id=com.intellij.database to be enabled"
        printf '%s\n' "  Plugin 'Micronaut' (com.intellij.micronaut) has dependency on 'JVM Persistence Frameworks' which cannot be loaded"
        printf '%s\n' "  Plugin 'Quarkus' (com.intellij.quarkus) has dependency on 'JVM Persistence Frameworks' which cannot be loaded"
        printf '%s\n' "  Plugin 'WildFly' (JBoss) has dependency on 'Jakarta EE: Application Servers' which cannot be loaded"
        printf '%s\n' "  Plugin 'Tomcat and TomEE' (Tomcat) has dependency on 'Jakarta EE: Application Servers' which cannot be loaded"
        printf '%s\n' 'java.lang.Throwable: Problems found loading plugins:'
    } > "${log}.database"
    append_plugin_manager_context "${log}.database"
    mybatis_assistant_classify_isolation_failures \
        "${log}.database" 2025.2.6.2 com.intellij.database \
        "${allowlisted}" "${unexpected}"
    [[ -s "${allowlisted}" && ! -s "${unexpected}" ]]

    sed "/JVM Persistence Frameworks.*requires plugin/d" \
        "${log}.database" > "${log}.database-without-parent"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.database-without-parent" 2025.2.6.2 com.intellij.database \
        "${allowlisted}" "${unexpected}"; then
        echo "未验证 Persistence 直接依赖时不得放行数据库传递错误" >&2
        return 1
    fi

    {
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - Problems found loading plugins:'
        printf '%s\n' "  Plugin 'Kotlin Notebook' (org.jetbrains.plugins.kotlin.jupyter) requires plugin with id=org.jetbrains.kotlin to be enabled"
        printf '%s\n' 'java.lang.Throwable: Problems found loading plugins:'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.u.c.ThreadingAssertions - Product exception'
    } > "${log}.product-error"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.product-error" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "产品异常不得被 bundled 依赖噪声掩盖" >&2
        return 1
    fi

    {
        sed -n '1,/java.lang.Throwable/p' "${log}"
        printf '%s\n' '2026-08-13 SEVERE - #c.i.u.c.ThreadingAssertions - Product exception'
        printf '%s\n' '2026-08-13 SEVERE - #c.i.i.p.PluginManager - OS: Linux'
    } > "${log}.non-adjacent-context"
    if mybatis_assistant_classify_isolation_failures \
        "${log}.non-adjacent-context" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "其他 SEVERE 之后的 PluginManager 上下文不应借用前一依赖异常" >&2
        return 1
    fi
}

verify_isolation_inspection_contract() {
    local output="${TEST_ROOT}/inspection"
    local evidence="${TEST_ROOT}/inspection-contract.tsv"
    local process_output="${TEST_ROOT}/inspect-process.log"
    local progress_evidence="${TEST_ROOT}/inspect-progress.tsv"
    local profile="/workspace/config/optional-dependency-isolation-profile.xml"
    mkdir -p "${output}"
    printf '%s\n' \
        '<inspections><inspection shortName="MyBatisUnusedStatement"/></inspections>' \
        > "${output}/.descriptions.xml"
    {
        printf '%s\n' '<problems>'
        printf '%s\n' '  <problem>'
        printf '%s\n' '    <file>file://$PROJECT_DIR$/src/main/resources/mappers/UserMapper.xml</file>'
        printf '%s\n' '    <line>12</line>'
        printf '%s\n' '    <problem_class id="MyBatisUnusedStatement">statement 未找到 Mapper 方法</problem_class>'
        printf '%s\n' '    <description>未找到对应的 Java Mapper 方法：io.github.mybatisideaassistant.lifecycle.UserMapper.findSummary</description>'
        printf '%s\n' '    <highlighted_element>&quot;findSummary&quot;</highlighted_element>'
        printf '%s\n' '  </problem>'
        printf '%s\n' '</problems>'
    } > "${output}/MyBatisUnusedStatement.xml"
    mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"
    grep -Fq -- $'2026-08-13.v1\tMyBatisUnusedStatement\t1\t1' "${evidence}"

    cp "${output}/.descriptions.xml" "${output}/.descriptions-valid.xml"
    sed 's/shortName="MyBatisUnusedStatement"/shortName="MyBatisUnusedStatementBackup"/' \
        "${output}/.descriptions-valid.xml" > "${output}/.descriptions.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "description shortName 碰瓷不得通过隔离检查契约" >&2
        return 1
    fi
    cp "${output}/.descriptions-valid.xml" "${output}/.descriptions.xml"

    cp "${output}/MyBatisUnusedStatement.xml" \
        "${output}/.inspection-valid.xml"
    sed 's/id="MyBatisUnusedStatement"/id="MyBatisOther"/' \
        "${output}/.inspection-valid.xml" \
        > "${output}/MyBatisUnusedStatement.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "错误 Inspection ID 不得通过隔离检查契约" >&2
        return 1
    fi
    sed 's#UserMapper.xml</file>#UserMapper.xml.bak</file>#' \
        "${output}/.inspection-valid.xml" \
        > "${output}/MyBatisUnusedStatement.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "文件后缀碰瓷不得通过隔离检查契约" >&2
        return 1
    fi
    sed 's/io.github.mybatisideaassistant.lifecycle.UserMapper.findSummary/io.github.mybatisideaassistant.lifecycle.UserMapperBackup.findSummary/' \
        "${output}/.inspection-valid.xml" \
        > "${output}/MyBatisUnusedStatement.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "namespace 碰瓷不得通过隔离检查契约" >&2
        return 1
    fi
    sed 's/findSummary/findSummaryExtra/g' \
        "${output}/.inspection-valid.xml" \
        > "${output}/MyBatisUnusedStatement.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "statement 碰瓷不得通过隔离检查契约" >&2
        return 1
    fi
    cp "${output}/.inspection-valid.xml" \
        "${output}/MyBatisUnusedStatement.xml"

    {
        printf '%s\n' 'Opening project…done.'
        printf "%s\n" "Initializing project…Loaded the 'MyBatis Assistant 可选依赖隔离验收' profile from the file '${profile}'"
        printf '%s\n' "Inspecting with the 'MyBatis Assistant 可选依赖隔离验收' profile"
        printf '%s\n' 'Scanning scope…'
        printf '%s\n' 'Analyzing code in …/src/main/java/io/github/mybatisideaassistant/lifecycle/UserMapper.java'
        printf '%s\n' 'Analyzing code in …/src/main/resources/mappers/UserMapper.xml'
        printf '%s\n' 'Done.'
    } > "${process_output}"
    mybatis_assistant_verify_inspect_progress \
        "${process_output}" "${profile}" \
        'MyBatis Assistant 可选依赖隔离验收' "${output}" \
        "${progress_evidence}"
    grep -Fq -- $'2026-08-13.v1\t1\t1\t1\t1\t1\t1\t1\t1' \
        "${progress_evidence}"
    sed '/src\/main\/java\/io\/github\/mybatisideaassistant\/lifecycle\/UserMapper.java/d' \
        "${process_output}" > "${process_output}.missing-java"
    if mybatis_assistant_verify_inspect_progress \
        "${process_output}.missing-java" "${profile}" \
        'MyBatis Assistant 可选依赖隔离验收' "${output}"; then
        echo "缺少 Java Mapper 分析证据不应判定项目已打开并完成检查" >&2
        return 1
    fi
    sed '/src\/main\/resources\/mappers\/UserMapper.xml/d' \
        "${process_output}" > "${process_output}.missing-xml"
    if mybatis_assistant_verify_inspect_progress \
        "${process_output}.missing-xml" "${profile}" \
        'MyBatis Assistant 可选依赖隔离验收' "${output}"; then
        echo "缺少 XML Mapper 分析证据不应判定项目已打开并完成检查" >&2
        return 1
    fi

    cp "${output}/MyBatisUnusedStatement.xml" "${output}/MyBatisOther.xml"
    if mybatis_assistant_verify_isolation_inspection_output \
        "${output}" "${evidence}"; then
        echo "额外 MyBatis 问题不得通过隔离检查契约" >&2
        return 1
    fi
}

verify_final_log_contract() {
    local shutdown_log="${TEST_ROOT}/shutdown.log"
    local shutdown_evidence="${TEST_ROOT}/shutdown-contract.tsv"
    local sandbox_log="${TEST_ROOT}/late-sandbox.log"
    local frozen_log="${TEST_ROOT}/late-frozen.log"
    local process_identity="${TEST_ROOT}/late-processes.tsv"
    local live_processes="${TEST_ROOT}/late-live.tsv"
    local late_ide_pid
    local late_ide_started_at
    local late_ide_command
    local fake_wrapper_pid
    local allowlisted="${TEST_ROOT}/late-allowlisted.log"
    local unexpected="${TEST_ROOT}/late-unexpected.log"

    printf '%s\n' \
        '2026-08-13 INFO - #c.i.p.p.ProjectEntitiesStorage - Project ProjectId(id=abc123) is disposed, removing entity' \
        '2026-08-13 INFO - #c.i.p.i.b.AppStarter - ------------------------------------------------------ IDE SHUTDOWN ------------------------------------------------------' \
        > "${shutdown_log}"
    mybatis_assistant_verify_final_shutdown_log \
        "${shutdown_log}" "${shutdown_evidence}"
    grep -Fq -- $'2026-08-13.v1\t1\t1\t1\t2' "${shutdown_evidence}"

    sed '/ProjectEntitiesStorage/d' "${shutdown_log}" \
        > "${shutdown_log}.missing-project-dispose"
    if mybatis_assistant_verify_final_shutdown_log \
        "${shutdown_log}.missing-project-dispose" "${shutdown_evidence}"; then
        echo "缺少项目 dispose 日志不得通过资源释放契约" >&2
        return 1
    fi
    sed '/IDE SHUTDOWN/d' "${shutdown_log}" \
        > "${shutdown_log}.missing-ide-shutdown"
    if mybatis_assistant_verify_final_shutdown_log \
        "${shutdown_log}.missing-ide-shutdown" "${shutdown_evidence}"; then
        echo "缺少 IDE SHUTDOWN 日志不得通过资源释放契约" >&2
        return 1
    fi
    {
        sed -n '2p' "${shutdown_log}"
        sed -n '1p' "${shutdown_log}"
    } > "${shutdown_log}.wrong-order"
    if mybatis_assistant_verify_final_shutdown_log \
        "${shutdown_log}.wrong-order" "${shutdown_evidence}"; then
        echo "IDE SHUTDOWN 早于项目 dispose 不得通过资源释放契约" >&2
        return 1
    fi

    : > "${sandbox_log}"
    : > "${process_identity}"
    : > "${live_processes}"
    /bin/bash -c '
        sleep 1
        /bin/bash -c '\''
            sleep 2
            printf "%s\n" "2026-08-13 SEVERE - #c.i.u.c.ThreadingAssertions - 新生子进程晚到产品异常" >> "$1"
        '\'' mybatis-late-idea-child "$1" &
        sleep 2
    ' mybatis-late-idea-parent "${sandbox_log}" &
    late_ide_pid=$!
    /bin/bash -c 'exit 0' &
    fake_wrapper_pid=$!
    wait "${fake_wrapper_pid}"
    mybatis_assistant_write_process_identity \
        "${late_ide_pid}" 'mybatis-late-idea-parent' "${process_identity}"
    late_ide_started_at="$(mybatis_assistant_process_started_at \
        "${late_ide_pid}")"
    late_ide_command="$(mybatis_assistant_process_command "${late_ide_pid}")"
    [[ ! -e "${frozen_log}" ]]
    mybatis_assistant_capture_final_idea_log_after_process_exit \
        "${late_ide_pid}" "${late_ide_started_at}" "${late_ide_command}" \
        "${process_identity}" "${live_processes}" \
        "${sandbox_log}" "${frozen_log}" 5
    wait "${late_ide_pid}" || true
    grep -Fq -- '新生子进程晚到产品异常' "${frozen_log}"
    if mybatis_assistant_classify_isolation_failures \
        "${frozen_log}" 2025.2.6.2 org.jetbrains.kotlin \
        "${allowlisted}" "${unexpected}"; then
        echo "wrapper 退出后由 IDEA 晚写的产品异常不得因过早冻结日志而漏检" >&2
        return 1
    fi
    grep -Fq -- '新生子进程晚到产品异常' "${unexpected}"
}

verify_dedicated_fixture_contract() {
    local fixture="${TEST_PROJECT_ROOT}/samples/lifecycle-inspection-corpus"
    local profile="${TEST_PROJECT_ROOT}/config/optional-dependency-isolation-profile.xml"
    local mapper_java="${fixture}/src/main/java/io/github/mybatisideaassistant/lifecycle/UserMapper.java"
    local mapper_xml="${fixture}/src/main/resources/mappers/UserMapper.xml"

    [[ -s "${fixture}/.idea/modules.xml" \
        && -s "${fixture}/lifecycle-inspection-corpus.iml" \
        && -s "${mapper_java}" && -s "${mapper_xml}" && -s "${profile}" ]]
    [[ "$(grep -c '<inspection_tool' "${profile}")" == "1" ]]
    grep -Fq -- 'class="MyBatisUnusedStatement"' "${profile}"
    grep -Fq -- 'interface UserMapper' "${mapper_java}"
    grep -Fq -- 'findById' "${mapper_java}"
    ! grep -Fq -- 'findSummary' "${mapper_java}"
    grep -Fq -- \
        'namespace="io.github.mybatisideaassistant.lifecycle.UserMapper"' \
        "${mapper_xml}"
    [[ "$(grep -c 'id="findById"' "${mapper_xml}")" == "1" ]]
    [[ "$(grep -c 'id="findSummary"' "${mapper_xml}")" == "1" ]]
    [[ ! -e "${fixture}/pom.xml" && ! -e "${fixture}/build.gradle" \
        && ! -e "${fixture}/build.gradle.kts" ]]
}

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

verify_isolation_failure_classifier
verify_isolation_inspection_contract
verify_final_log_contract
verify_dedicated_fixture_contract

echo "可选依赖隔离 Shell 契约测试通过"
