#!/usr/bin/env bash

set -euo pipefail

version="${1:-}"
changelog="${2:-CHANGELOG.md}"
output="${3:-}"

if [[ -z "${version}" || -z "${output}" ]]; then
  echo "用法：extract-release-notes.sh VERSION CHANGELOG OUTPUT" >&2
  exit 1
fi
if [[ ! -f "${changelog}" ]]; then
  echo "找不到变更记录：${changelog}" >&2
  exit 1
fi

awk -v expected_version="${version}" '
  /^## / {
    heading = $0
    sub(/^##[[:space:]]+\[/, "", heading)
    sub(/^##[[:space:]]+/, "", heading)
    sub(/\].*$/, "", heading)
    sub(/[[:space:]]+-[[:space:]].*$/, "", heading)
    if (heading == expected_version) {
      found = 1
      print
      next
    }
  }
  found && /^## / { exit }
  found { print }
  END {
    if (!found) {
      exit 42
    }
  }
' "${changelog}" > "${output}" || {
  status=$?
  if [[ "${status}" -eq 42 ]]; then
    echo "CHANGELOG.md 缺少版本 ${version} 的独立发布章节" >&2
  fi
  rm -f "${output}"
  exit "${status}"
}

if ! grep -Eq '^[[:space:]]*[-*][[:space:]]+[^[:space:]]' "${output}"; then
  echo "版本 ${version} 的发布说明为空" >&2
  rm -f "${output}"
  exit 1
fi
