#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
validator="${script_dir}/validate-release-inputs.sh"

assert_success() {
  local version="$1"
  local channel="$2"
  local event_name="$3"
  local ref_type="$4"
  local ref_name="$5"
  local expected_version="$6"
  local expected_channel="$7"
  local expected_prerelease="$8"
  local output

  output="$("${validator}" "${version}" "${channel}" "${event_name}" "${ref_type}" "${ref_name}")"
  grep -qx "version=${expected_version}" <<<"${output}"
  grep -qx "channel=${expected_channel}" <<<"${output}"
  grep -qx "prerelease=${expected_prerelease}" <<<"${output}"
  grep -qx "tag=${ref_name}" <<<"${output}"
}

assert_failure() {
  if "${validator}" "$@" >/dev/null 2>&1; then
    printf '预期发布输入被拒绝，但校验却通过：%s\n' "$*" >&2
    exit 1
  fi
}

assert_success "" "" push tag v1.0.0 1.0.0 default false
assert_success "" "" push tag v1.0.0-alpha.1 1.0.0-alpha.1 alpha true
assert_success "1.2.3-beta.4" beta workflow_dispatch tag v1.2.3-beta.4 1.2.3-beta.4 beta true
assert_success "2.0.0-rc.12" rc workflow_dispatch tag v2.0.0-rc.12 2.0.0-rc.12 rc true

assert_failure "1.0.0" default workflow_dispatch branch main
assert_failure "1.0.0-SNAPSHOT" default workflow_dispatch tag v1.0.0-SNAPSHOT
assert_failure "1.0.0" alpha workflow_dispatch tag v1.0.0
assert_failure "1.0.0-rc.1" beta workflow_dispatch tag v1.0.0-rc.1
assert_failure "1.0.1" default workflow_dispatch tag v1.0.0
assert_failure "1.0.0-alpha.0" alpha workflow_dispatch tag v1.0.0-alpha.0
assert_failure "01.0.0" default workflow_dispatch tag v01.0.0
assert_failure "1.0.0-preview.1" rc workflow_dispatch tag v1.0.0-preview.1
assert_failure "" "" schedule tag v1.0.0
assert_failure "" "" push tag release-1.0.0

printf '发布版本、标签与渠道校验测试通过\n'
