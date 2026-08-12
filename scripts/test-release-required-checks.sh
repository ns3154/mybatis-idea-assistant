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

success_fixture="${temporary_dir}/success.json"
write_fixture "${success_fixture}"
MYBATIS_ASSISTANT_CHECK_RUNS_FILE="${success_fixture}" \
  "${verifier}" "${sha}" owner/repo >/dev/null

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
