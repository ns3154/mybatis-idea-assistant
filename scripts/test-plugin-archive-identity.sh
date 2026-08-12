#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verifier="${script_dir}/verify-plugin-archive-identity.sh"
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-plugin-identity-test.XXXXXX")"
trap 'rm -rf -- "${temporary_dir}"' EXIT

mkdir -p "${temporary_dir}/jar/META-INF" \
  "${temporary_dir}/archive/mybatis-idea-assistant/lib"
printf '%s\n' \
  '<idea-plugin>' \
  '  <id>io.github.ns3154.mybatis-idea-assistant</id>' \
  '  <version>1.2.3-rc.4</version>' \
  '</idea-plugin>' \
  > "${temporary_dir}/jar/META-INF/plugin.xml"
printf 'version=1.2.3-rc.4\n' \
  > "${temporary_dir}/jar/META-INF/mybatis-assistant-version.properties"
(
  cd "${temporary_dir}/jar"
  zip -qr "${temporary_dir}/archive/mybatis-idea-assistant/lib/mybatis-idea-assistant-1.2.3-rc.4.jar" .
)
(
  cd "${temporary_dir}/archive"
  zip -qr "${temporary_dir}/candidate.zip" .
)

"${verifier}" "${temporary_dir}/candidate.zip" 1.2.3-rc.4 >/dev/null
if "${verifier}" "${temporary_dir}/candidate.zip" 1.2.3 >/dev/null 2>&1; then
  echo "错误版本的候选被接受" >&2
  exit 1
fi

echo "插件候选身份契约测试通过"
