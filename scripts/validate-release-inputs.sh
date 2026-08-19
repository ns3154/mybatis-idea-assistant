#!/usr/bin/env bash

set -euo pipefail

input_version="${1:-}"
input_channel="${2:-}"
event_name="${3:-}"
ref_type="${4:-}"
ref_name="${5:-}"

fail() {
  printf '发布输入不合法：%s\n' "$1" >&2
  exit 1
}

if [[ "${ref_type}" != "tag" ]]; then
  fail "只能从不可变的 v* Git 标签构建或发布候选，当前引用类型为 ${ref_type:-空}"
fi
if [[ ! "${ref_name}" =~ ^v(.+)$ ]]; then
  fail "标签必须以 v 开头"
fi

version="${BASH_REMATCH[1]}"
case "${version}" in
  *SNAPSHOT*|*snapshot*)
    fail "任何发布渠道都禁止 SNAPSHOT 版本"
    ;;
esac

if [[ "${version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  expected_channel="default"
  prerelease="false"
elif [[ "${version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-(alpha|beta|rc)\.([1-9][0-9]*)$ ]]; then
  expected_channel="${BASH_REMATCH[4]}"
  prerelease="true"
else
  fail "版本只能是 X.Y.Z、X.Y.Z-alpha.N、X.Y.Z-beta.N 或 X.Y.Z-rc.N"
fi

if [[ "${event_name}" == "workflow_dispatch" ]]; then
  if [[ -z "${input_version}" || -z "${input_channel}" ]]; then
    fail "手动运行必须显式填写版本和渠道"
  fi
  if [[ "${input_version}" != "${version}" ]]; then
    fail "输入版本 ${input_version} 与标签 ${ref_name} 不一致"
  fi
  if [[ "${input_channel}" != "${expected_channel}" ]]; then
    fail "版本 ${version} 必须使用 ${expected_channel} 渠道，不能使用 ${input_channel}"
  fi
elif [[ "${event_name}" != "push" ]]; then
  fail "只接受标签推送或手动运行，当前事件为 ${event_name:-空}"
fi

printf 'version=%s\n' "${version}"
printf 'channel=%s\n' "${expected_channel}"
printf 'prerelease=%s\n' "${prerelease}"
printf 'tag=%s\n' "${ref_name}"
