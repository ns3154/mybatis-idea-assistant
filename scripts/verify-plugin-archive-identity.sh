#!/usr/bin/env bash

set -euo pipefail

archive="${1:-}"
expected_version="${2:-}"
expected_id="io.github.ns3154.mybatis-idea-assistant"

if [[ ! -f "${archive}" ]]; then
  echo "找不到插件候选：${archive}" >&2
  exit 2
fi
if [[ ! "${expected_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[1-9][0-9]*)?$ \
    && "${expected_version}" != "0.1.0-SNAPSHOT" ]]; then
  echo "待核对插件版本不合法：${expected_version}" >&2
  exit 2
fi

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-plugin-identity.XXXXXX")"
trap 'rm -rf -- "${temporary_dir}"' EXIT

plugin_jars=()
while IFS= read -r entry; do
  plugin_jars+=("${entry}")
done < <(unzip -Z1 "${archive}" \
  | grep -E '^mybatis-idea-assistant/lib/mybatis-idea-assistant-[^/]+\.jar$' || true)
if [[ "${#plugin_jars[@]}" -ne 1 ]]; then
  echo "插件候选必须包含恰好一个主插件 JAR，实际为 ${#plugin_jars[@]}" >&2
  exit 1
fi

plugin_jar="${temporary_dir}/plugin.jar"
unzip -p "${archive}" "${plugin_jars[0]}" > "${plugin_jar}"
plugin_xml="$(unzip -p "${plugin_jar}" META-INF/plugin.xml)"
version_properties="$(unzip -p \
  "${plugin_jar}" META-INF/mybatis-assistant-version.properties)"

actual_id="$(sed -n 's:.*<id>\([^<]*\)</id>.*:\1:p' <<<"${plugin_xml}" | head -n 1)"
actual_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' \
  <<<"${plugin_xml}" | head -n 1)"
resource_version="$(sed -n 's/^version=//p' <<<"${version_properties}" | head -n 1)"

if [[ "${actual_id}" != "${expected_id}" ]]; then
  echo "插件 ID 不匹配：${actual_id}" >&2
  exit 1
fi
if [[ "${actual_version}" != "${expected_version}" ]]; then
  echo "plugin.xml 版本不匹配：${actual_version} != ${expected_version}" >&2
  exit 1
fi
if [[ "${resource_version}" != "${expected_version}" ]]; then
  echo "运行时版本资源不匹配：${resource_version} != ${expected_version}" >&2
  exit 1
fi

printf '插件候选身份已确认：%s %s\n' "${expected_id}" "${expected_version}"
