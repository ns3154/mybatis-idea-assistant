#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
extractor="${script_dir}/extract-release-notes.sh"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-release-notes.XXXXXX")"
trap 'rm -rf "${temp_dir}"' EXIT

changelog="${temp_dir}/CHANGELOG.md"
output="${temp_dir}/notes.md"
printf '%s\n' \
  '# 变更记录' \
  '' \
  '## 未发布' \
  '' \
  '- 尚未发布的内容。' \
  '' \
  '## [1.0.0-rc.1] - 2026-08-12' \
  '' \
  '### 新增' \
  '' \
  '- 候选功能。' \
  '' \
  '## [0.9.0] - 2026-08-01' \
  '' \
  '- 旧版本。' > "${changelog}"

"${extractor}" 1.0.0-rc.1 "${changelog}" "${output}"
grep -qx '## \[1.0.0-rc.1\] - 2026-08-12' "${output}"
grep -qx -- '- 候选功能。' "${output}"
if grep -q '旧版本' "${output}"; then
  echo "版本发布说明越过了下一个版本边界" >&2
  exit 1
fi

if "${extractor}" 1.0.0 "${changelog}" "${output}" >/dev/null 2>&1; then
  echo "缺少版本章节时不应生成发布说明" >&2
  exit 1
fi

printf '版本化发布说明提取测试通过\n'
