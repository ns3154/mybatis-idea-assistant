#!/usr/bin/env bash

set -euo pipefail

candidate_sha="${1:-}"
repository="${2:-${GITHUB_REPOSITORY:-}}"
fixture_file="${MYBATIS_ASSISTANT_CHECK_RUNS_FILE:-}"

if [[ ! "${candidate_sha}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "候选提交必须是完整的 40 位小写 SHA" >&2
  exit 2
fi
if [[ -z "${repository}" || "${repository}" != */* ]]; then
  echo "仓库必须使用 owner/repo 格式" >&2
  exit 2
fi

required_checks=(
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

temporary_file=""
cleanup() {
  if [[ -n "${temporary_file}" ]]; then
    rm -f -- "${temporary_file}"
  fi
}
trap cleanup EXIT

if [[ -n "${fixture_file}" ]]; then
  [[ -f "${fixture_file}" ]] || {
    echo "找不到检查结果夹具：${fixture_file}" >&2
    exit 2
  }
  check_runs_file="${fixture_file}"
else
  command -v gh >/dev/null || {
    echo "缺少 gh，无法读取候选提交的检查结果" >&2
    exit 2
  }
  temporary_file="$(mktemp "${TMPDIR:-/tmp}/mybatis-release-checks.XXXXXX")"
  gh api --paginate --slurp \
    -H 'Accept: application/vnd.github+json' \
    "repos/${repository}/commits/${candidate_sha}/check-runs?per_page=100" \
    > "${temporary_file}"
  check_runs_file="${temporary_file}"
fi

failed=0
for required in "${required_checks[@]}"; do
  check_name="${required%%|*}"
  app_slug="${required#*|}"
  latest="$({
    jq -c \
      --arg name "${check_name}" \
      --arg app "${app_slug}" \
      --arg sha "${candidate_sha}" '
        [.. | objects
          | select(has("name") and has("head_sha") and has("status"))
          | select(.name == $name and .app.slug == $app and .head_sha == $sha)]
        | if length == 0 then null else max_by(.id) end
      ' "${check_runs_file}"
  } 2>/dev/null)"
  if [[ -z "${latest}" || "${latest}" == "null" ]]; then
    printf '缺少发布必需检查：%s（%s）\n' "${check_name}" "${app_slug}" >&2
    failed=1
    continue
  fi
  status="$(jq -r '.status' <<<"${latest}")"
  conclusion="$(jq -r '.conclusion // ""' <<<"${latest}")"
  details_url="$(jq -r '.details_url // ""' <<<"${latest}")"
  if [[ "${status}" != "completed" || "${conclusion}" != "success" ]]; then
    printf '发布必需检查未成功：%s status=%s conclusion=%s %s\n' \
      "${check_name}" "${status}" "${conclusion}" "${details_url}" >&2
    failed=1
  fi
done

if (( failed != 0 )); then
  exit 1
fi

printf '候选提交 %s 的全部发布必需检查均已成功\n' "${candidate_sha}"
