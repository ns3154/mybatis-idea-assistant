#!/usr/bin/env bash

set -euo pipefail

candidate_sha="${1:-}"
repository="${2:-${GITHUB_REPOSITORY:-}}"
fixture_file="${MYBATIS_ASSISTANT_CHECK_RUNS_FILE:-}"
analyses_fixture_file="${MYBATIS_ASSISTANT_CODE_SCANNING_ANALYSES_FILE:-}"

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

temporary_checks_file=""
temporary_analyses_file=""
cleanup() {
  if [[ -n "${temporary_checks_file}" ]]; then
    rm -f -- "${temporary_checks_file}"
  fi
  if [[ -n "${temporary_analyses_file}" ]]; then
    rm -f -- "${temporary_analyses_file}"
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
  temporary_checks_file="$(mktemp "${TMPDIR:-/tmp}/mybatis-release-checks.XXXXXX")"
  gh api --paginate --slurp \
    -H 'Accept: application/vnd.github+json' \
    "repos/${repository}/commits/${candidate_sha}/check-runs?per_page=100" \
    > "${temporary_checks_file}"
  check_runs_file="${temporary_checks_file}"
fi

latest_check() {
  local check_name="$1"
  local app_slug="$2"
  jq -c \
    --arg name "${check_name}" \
    --arg app "${app_slug}" \
    --arg sha "${candidate_sha}" '
      [.. | objects
        | select(has("name") and has("head_sha") and has("status"))
        | select(.name == $name and .app.slug == $app and .head_sha == $sha)]
      | if length == 0 then null else max_by(.id) end
    ' "${check_runs_file}" 2>/dev/null
}

validate_successful_check() {
  local latest="$1"
  local check_name="$2"
  local app_slug="$3"
  local status
  local conclusion
  local details_url

  if [[ -z "${latest}" || "${latest}" == "null" ]]; then
    printf '缺少发布必需检查：%s（%s）\n' "${check_name}" "${app_slug}" >&2
    return 1
  fi
  status="$(jq -r '.status' <<<"${latest}")"
  conclusion="$(jq -r '.conclusion // ""' <<<"${latest}")"
  details_url="$(jq -r '.details_url // ""' <<<"${latest}")"
  if [[ "${status}" != "completed" || "${conclusion}" != "success" ]]; then
    printf '发布必需检查未成功：%s status=%s conclusion=%s %s\n' \
      "${check_name}" "${status}" "${conclusion}" "${details_url}" >&2
    return 1
  fi
}

failed=0
for required in "${required_checks[@]}"; do
  check_name="${required%%|*}"
  app_slug="${required#*|}"
  latest="$(latest_check "${check_name}" "${app_slug}")"
  if ! validate_successful_check "${latest}" "${check_name}" "${app_slug}"; then
    failed=1
  fi
done

codeql_check="$(latest_check "CodeQL" "github-advanced-security")"
if [[ -n "${codeql_check}" && "${codeql_check}" != "null" ]]; then
  if ! validate_successful_check \
    "${codeql_check}" "CodeQL" "github-advanced-security"; then
    failed=1
  fi
else
  if [[ -n "${analyses_fixture_file}" ]]; then
    [[ -f "${analyses_fixture_file}" ]] || {
      echo "找不到 CodeQL 分析夹具：${analyses_fixture_file}" >&2
      exit 2
    }
    analyses_file="${analyses_fixture_file}"
  elif [[ -n "${fixture_file}" ]]; then
    echo "检查夹具缺少独立 CodeQL 检查时，必须同时提供 CodeQL 分析夹具" >&2
    exit 2
  else
    temporary_analyses_file="$(
      mktemp "${TMPDIR:-/tmp}/mybatis-release-codeql-analyses.XXXXXX"
    )"
    gh api --method GET --paginate --slurp \
      -H 'Accept: application/vnd.github+json' \
      -f ref='refs/heads/main' \
      -f per_page=100 \
      "repos/${repository}/code-scanning/analyses" \
      > "${temporary_analyses_file}"
    analyses_file="${temporary_analyses_file}"
  fi

  latest_analysis="$(
    jq -c \
      --arg sha "${candidate_sha}" '
        [.. | objects
          | select(.commit_sha? == $sha)
          | select(.ref? == "refs/heads/main")
          | select(.analysis_key? == ".github/workflows/security.yml:codeql")
          | select(.category? == "/language:java-kotlin")
          | select(.tool.name? == "CodeQL")]
        | if length == 0 then null else max_by(.id) end
      ' "${analyses_file}" 2>/dev/null
  )"
  if [[ -z "${latest_analysis}" || "${latest_analysis}" == "null" ]]; then
    echo "缺少候选提交在 main 上的精确 CodeQL Java/Kotlin 分析证据" >&2
    failed=1
  else
    analysis_error="$(jq -r '.error // ""' <<<"${latest_analysis}")"
    analysis_warning="$(jq -r '.warning // ""' <<<"${latest_analysis}")"
    results_count="$(jq -r '.results_count // -1' <<<"${latest_analysis}")"
    rules_count="$(jq -r '.rules_count // 0' <<<"${latest_analysis}")"
    if [[ -n "${analysis_error}" || -n "${analysis_warning}" \
      || "${results_count}" != "0" \
      || ! "${rules_count}" =~ ^[1-9][0-9]*$ ]]; then
      printf 'CodeQL 分析证据不满足发布要求：error=%s warning=%s results=%s rules=%s\n' \
        "${analysis_error}" "${analysis_warning}" \
        "${results_count}" "${rules_count}" >&2
      failed=1
    fi
  fi
fi

if (( failed != 0 )); then
  exit 1
fi

printf '候选提交 %s 的全部发布必需检查与 CodeQL 证据均已成功\n' "${candidate_sha}"
