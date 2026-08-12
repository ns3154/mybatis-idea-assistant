#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/.." && pwd)"
release_workflow="${repository_root}/.github/workflows/release.yml"
marketplace_workflow="${repository_root}/.github/workflows/marketplace.yml"

require_text() {
  local file="$1"
  local expected="$2"
  local description="$3"
  if ! grep -F "${expected}" "${file}" >/dev/null; then
    echo "发布工作流缺少${description}：${expected}" >&2
    exit 1
  fi
}

require_text "${release_workflow}" 'GH_TOKEN: ${{ github.token }}' \
  "必需检查 API 认证"
require_text "${release_workflow}" \
  'signed_zip="${unsigned_zip%.zip}-signed.zip"' \
  "输入候选同级签名产物定位"
require_text "${release_workflow}" \
  "find build/release-input -maxdepth 1 -type f -name '*-signed.zip' -print" \
  "签名产物固化目录"

main_guards="$(grep -Fxc "    if: github.ref == 'refs/heads/main'" \
  "${marketplace_workflow}" || true)"
if [[ "${main_guards}" -ne 2 ]]; then
  echo "Marketplace 发布和最终公开作业都必须限制从 main 调度" >&2
  exit 1
fi

repository_envs="$({ grep -F "          GH_REPO: \${{ github.repository }}" \
  "${release_workflow}"; grep -F "          GH_REPO: \${{ github.repository }}" \
  "${marketplace_workflow}"; } | wc -l | tr -d ' ')"
if [[ "${repository_envs}" -ne 2 ]]; then
  echo "两个无检出 Release 作业都必须显式固定目标仓库" >&2
  exit 1
fi

echo "发布工作流静态契约测试通过"
