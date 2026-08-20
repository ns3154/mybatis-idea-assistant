#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="${script_dir}/verify-release-required-checks.sh"
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-release-checks-test.XXXXXX")"
trap 'rm -rf -- "${temporary_dir}"' EXIT
sha="0123456789abcdef0123456789abcdef01234567"

required_names=(
  "构建、测试与兼容验证|github-actions"
  "CodeQL Java/Kotlin|github-actions"
  "CodeQL|github-advanced-security"
  "Linux 自动化回归|github-actions"
  "macOS 自动化回归|github-actions"
  "Windows 自动化回归|github-actions"
  "IDE 生命周期 100 次|github-actions"
  "可选依赖隔离 5/5|github-actions"
  "验证 IntelliJ IDEA 2025.2.6.2|github-actions"
  "验证 IntelliJ IDEA 2025.3.6|github-actions"
  "验证 IntelliJ IDEA 2026.1.4|github-actions"
  "验证 IntelliJ IDEA 2026.2.0.1|github-actions"
  "验证 Android Studio 2026.1.2.10|github-actions"
)

write_fixture() {
  local output="$1"
  local overridden_name="${2:-}"
  local overridden_status="${3:-completed}"
  local overridden_conclusion="${4:-success}"
  local id=1
  local entry
  local name
  local app
  local status
  local conclusion

  printf '{"check_runs":[' > "${output}"
  for entry in "${required_names[@]}"; do
    name="${entry%%|*}"
    app="${entry#*|}"
    status="completed"
    conclusion="success"
    if [[ "${name}" == "${overridden_name}" ]]; then
      status="${overridden_status}"
      conclusion="${overridden_conclusion}"
    fi
    if (( id > 1 )); then
      printf ',' >> "${output}"
    fi
    jq -cn \
      --argjson id "${id}" \
      --arg name "${name}" \
      --arg app "${app}" \
      --arg sha "${sha}" \
      --arg status "${status}" \
      --arg conclusion "${conclusion}" \
      '{id:$id,name:$name,app:{slug:$app},head_sha:$sha,status:$status,
        conclusion:$conclusion,details_url:"https://example.invalid/check"}' \
      >> "${output}"
    id=$((id + 1))
  done
  printf ']}' >> "${output}"
}

write_analysis_fixture() {
  local output="$1"
  local analysis_sha="${2:-${sha}}"
  local analysis_error="${3:-}"
  local analysis_warning="${4:-}"
  local results_count="${5:-0}"
  local rules_count="${6:-76}"
  local analysis_ref="${7:-refs/heads/main}"
  local analysis_key="${8:-.github/workflows/security.yml:codeql}"
  local category="${9:-/language:java-kotlin}"
  local tool_name="${10:-CodeQL}"

  jq -cn \
    --arg sha "${analysis_sha}" \
    --arg error "${analysis_error}" \
    --arg warning "${analysis_warning}" \
    --argjson results "${results_count}" \
    --argjson rules "${rules_count}" \
    --arg ref "${analysis_ref}" \
    --arg key "${analysis_key}" \
    --arg category "${category}" \
    --arg tool "${tool_name}" '
      [{id:1001,commit_sha:$sha,ref:$ref,analysis_key:$key,category:$category,
        tool:{name:$tool,version:"2.26.3"},error:$error,warning:$warning,
        results_count:$results,rules_count:$rules,
        created_at:"2026-08-20T00:00:00Z"}]
    ' > "${output}"
}

success_fixture="${temporary_dir}/success.json"
write_fixture "${success_fixture}"
MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${success_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null

analysis_fixture="${temporary_dir}/analysis-success.json"
write_analysis_fixture "${analysis_fixture}"
fallback_fixture="${temporary_dir}/fallback.json"
jq '
  .check_runs |= map(
    select(.name != "CodeQL" or .app.slug != "github-advanced-security")
  )
' "${success_fixture}" > "${fallback_fixture}"
MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null

if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "缺少独立 CodeQL 检查时错误跳过了分析证据" >&2
  exit 1
fi

codeql_failed_fixture="${temporary_dir}/codeql-failed.json"
write_fixture "${codeql_failed_fixture}" "CodeQL" completed failure
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${codeql_failed_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "失败的独立 CodeQL 检查被错误接受" >&2
  exit 1
fi

analysis_wrong_sha="${temporary_dir}/analysis-wrong-sha.json"
write_analysis_fixture \
  "${analysis_wrong_sha}" "1123456789abcdef0123456789abcdef01234567"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_wrong_sha}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "其他提交的 CodeQL 分析被错误接受" >&2
  exit 1
fi

analysis_wrong_identity="${temporary_dir}/analysis-wrong-identity.json"
write_analysis_fixture \
  "${analysis_wrong_identity}" "${sha}" "" "" 0 76 \
  "refs/heads/main" ".github/workflows/other.yml:codeql"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_wrong_identity}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "其他工作流的 CodeQL 分析被错误接受" >&2
  exit 1
fi

analysis_with_error="${temporary_dir}/analysis-error.json"
write_analysis_fixture "${analysis_with_error}" "${sha}" "upload failed"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_with_error}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "包含错误的 CodeQL 分析被错误接受" >&2
  exit 1
fi

analysis_with_warning="${temporary_dir}/analysis-warning.json"
write_analysis_fixture "${analysis_with_warning}" "${sha}" "" "partial analysis"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_with_warning}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "包含告警的 CodeQL 分析被错误接受" >&2
  exit 1
fi

analysis_with_results="${temporary_dir}/analysis-results.json"
write_analysis_fixture "${analysis_with_results}" "${sha}" "" "" 1
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_with_results}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "包含 CodeQL 结果的分析被错误接受" >&2
  exit 1
fi

analysis_without_rules="${temporary_dir}/analysis-no-rules.json"
write_analysis_fixture "${analysis_without_rules}" "${sha}" "" "" 0 0
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_without_rules}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "未执行规则的 CodeQL 分析被错误接受" >&2
  exit 1
fi

analysis_with_later_failure="${temporary_dir}/analysis-later-failure.json"
jq '
  . += [.[0] + {id:2000,error:"later failure",created_at:"2026-08-20T00:01:00Z"}]
' "${analysis_fixture}" > "${analysis_with_later_failure}"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${fallback_fixture}" \
  MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE="${analysis_with_later_failure}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "较新的失败 CodeQL 分析被旧成功分析掩盖" >&2
  exit 1
fi

failed_fixture="${temporary_dir}/failed.json"
write_fixture "${failed_fixture}" "Windows 自动化回归" completed failure
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${failed_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "失败的最新检查被错误接受" >&2
  exit 1
fi

duplicate_fixture="${temporary_dir}/duplicate.json"
write_fixture "${duplicate_fixture}"
jq --arg sha "${sha}" \
  '.check_runs += [{id:999,name:"Linux 自动化回归",app:{slug:"github-actions"},
    head_sha:$sha,status:"completed",conclusion:"failure"}]' \
  "${duplicate_fixture}" > "${temporary_dir}/duplicate-latest.json"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${temporary_dir}/duplicate-latest.json" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "同名检查的较新失败结果被旧成功结果掩盖" >&2
  exit 1
fi

missing_fixture="${temporary_dir}/missing.json"
jq 'del(.check_runs[-1])' "${success_fixture}" > "${missing_fixture}"
if MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${missing_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null 2>&1; then
  echo "缺失发布检查被错误接受" >&2
  exit 1
fi

echo "发布必需检查聚合契约测试通过"
