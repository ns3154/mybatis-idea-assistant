#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repository_root}"

unsigned_archive="${1:-}"
if [[ -z "${unsigned_archive}" ]]; then
  unsigned_archives=()
  while IFS= read -r archive; do
    unsigned_archives+=("${archive}")
  done < <(find build/distributions -maxdepth 1 -type f \
    -name '*.zip' ! -name '*-signed.zip' -print)
  if [[ "${#unsigned_archives[@]}" -ne 1 ]]; then
    echo "临时签名要求恰好一个未签名 ZIP，实际为 ${#unsigned_archives[@]}" >&2
    exit 1
  fi
  unsigned_archive="${unsigned_archives[0]}"
fi
if [[ ! -f "${unsigned_archive}" ]]; then
  echo "找不到未签名 ZIP：${unsigned_archive}" >&2
  exit 1
fi

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/mybatis-ephemeral-signing.XXXXXX")"
report_dir="build/reports/ephemeral-signing"
signed_archive="build/distributions/ephemeral-test-candidate-signed.zip"
trap 'rm -rf "${temp_dir}"; rm -f "${signed_archive}"' EXIT
mkdir -p "${report_dir}"

private_password="ephemeral-ci-only"
openssl genpkey \
  -algorithm RSA \
  -out "${temp_dir}/private-raw.pem" \
  -pkeyopt rsa_keygen_bits:2048 \
  >/dev/null 2>&1
openssl pkcs8 \
  -topk8 \
  -in "${temp_dir}/private-raw.pem" \
  -out "${temp_dir}/private-encrypted.pem" \
  -v1 PBE-SHA1-3DES \
  -passout "pass:${private_password}"
openssl req \
  -key "${temp_dir}/private-raw.pem" \
  -new \
  -x509 \
  -sha256 \
  -days 1 \
  -subj '/CN=MyBatis Assistant CI Test Signing/O=Ephemeral CI/C=CN' \
  -out "${temp_dir}/chain.crt"
chmod 600 "${temp_dir}/private-encrypted.pem" "${temp_dir}/private-raw.pem"

cp "${unsigned_archive}" "${temp_dir}/ephemeral-test-candidate.zip"
certificate_chain="$(<"${temp_dir}/chain.crt")"
private_key="$(<"${temp_dir}/private-encrypted.pem")"

CERTIFICATE_CHAIN="${certificate_chain}" \
PRIVATE_KEY="${private_key}" \
PRIVATE_KEY_PASSWORD="${private_password}" \
./scripts/run-gradle-with-infrastructure-retry.sh \
  signPlugin -x buildPlugin \
  -PpluginSigningArchiveFile="${temp_dir}/ephemeral-test-candidate.zip"

generated_signed_archive="$(
  find "${temp_dir}" build/distributions -maxdepth 1 -type f \
    -name 'ephemeral-test-candidate-signed.zip' -print -quit
)"
if [[ -z "${generated_signed_archive}" ]]; then
  echo "未生成临时签名 ZIP" >&2
  exit 1
fi
if [[ "${generated_signed_archive}" != "${signed_archive}" ]]; then
  cp "${generated_signed_archive}" "${signed_archive}"
  rm -f "${generated_signed_archive}"
fi

./scripts/run-gradle-with-infrastructure-retry.sh \
  verifyPluginSignature -x signPlugin -x buildPlugin \
  -PpluginSignatureVerificationArchiveFile="${signed_archive}" \
  -PpluginSignatureCertificateFile="${temp_dir}/chain.crt"

cp "${signed_archive}" "${temp_dir}/tampered.zip"
printf '此文件用于证明篡改后验签失败。\n' > "${temp_dir}/tamper-marker.txt"
(
  cd "${temp_dir}"
  zip -q tampered.zip tamper-marker.txt
)
if ./scripts/run-gradle-with-infrastructure-retry.sh \
  verifyPluginSignature -x signPlugin -x buildPlugin \
  -PpluginSignatureVerificationArchiveFile="${temp_dir}/tampered.zip" \
  -PpluginSignatureCertificateFile="${temp_dir}/chain.crt" \
  >"${temp_dir}/tampered-verification.log" 2>&1; then
  echo "篡改后的 ZIP 被错误接受" >&2
  exit 1
fi

if unzip -p "${signed_archive}" | strings | \
  grep -E 'BEGIN (RSA |EC |ENCRYPTED )?PRIVATE KEY|PRIVATE_KEY_PASSWORD|PUBLISH_TOKEN' \
    >/dev/null; then
  echo "临时签名制品包含私钥或发布凭据标记" >&2
  exit 1
fi

unsigned_sha="$(shasum -a 256 "${unsigned_archive}" | awk '{print $1}')"
signed_sha="$(shasum -a 256 "${signed_archive}" | awk '{print $1}')"
certificate_fingerprint="$(
  openssl x509 -in "${temp_dir}/chain.crt" -noout -fingerprint -sha256 |
    cut -d= -f2
)"
cat > "${report_dir}/report.txt" <<EOF
用途=临时测试签名，不可分发
未签名制品_SHA256=${unsigned_sha}
测试签名制品_SHA256=${signed_sha}
测试证书_SHA256=${certificate_fingerprint}
正常签名验证=通过
篡改签名验证=按预期拒绝
私钥与发布凭据扫描=通过
EOF

echo "临时测试签名、正常验签与篡改拒绝全部通过"
