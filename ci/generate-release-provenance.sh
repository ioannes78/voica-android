#!/usr/bin/env bash
set -euo pipefail

required_env=(
    VOICA_VERSION_CODE
    VOICA_RELEASE_VERSION
    VOICA_RELEASEQA_VERSION
    VOICA_MODEL_CHANNEL_COMMIT
    VOICA_MODEL_MANIFEST_VERSION
    VOICA_MODEL_MANIFEST_DIGEST
)
for name in "${required_env[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required environment variable: ${name}" >&2
        exit 2
    fi
done

release_apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
release_aab="${2:-app/build/outputs/bundle/release/app-release.aab}"
releaseqa_apk="${3:-app/build/outputs/apk/releaseQa/app-releaseQa.apk}"
out="${4:-build/release-provenance.txt}"

for path in "${release_apk}" "${release_aab}" "${releaseqa_apk}"; do
    test -f "${path}"
done

mkdir -p "$(dirname "${out}")"

sha256() {
    sha256sum "$1" | awk '{print $1}'
}
size_bytes() {
    stat -c %s "$1"
}

source_head_sha="${VOICA_SOURCE_HEAD_SHA:-${GITHUB_SHA:-unknown}}"
workflow_build_sha="${GITHUB_SHA:-unknown}"

signer="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/build-tools/36.0.0/apksigner"
production_signed=false
production_cert_sha256="UNPROVISIONED"
if "${signer}" verify "${release_apk}" >/dev/null 2>&1; then
    production_signed=true
    production_cert_sha256="$(
        "${signer}" verify --print-certs "${release_apk}" |
            sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
            tr '[:upper:]' '[:lower:]'
    )"
fi

cat > "${out}" <<EOF
source_head_sha=${source_head_sha}
workflow_build_sha=${workflow_build_sha}
version_code=${VOICA_VERSION_CODE}
release_version_name=${VOICA_RELEASE_VERSION}
releaseqa_version_name=${VOICA_RELEASEQA_VERSION}
room_schema=12
abi=arm64-v8a
production_model_channel_commit=${VOICA_MODEL_CHANNEL_COMMIT}
production_model_manifest_version=${VOICA_MODEL_MANIFEST_VERSION}
production_model_manifest_digest=${VOICA_MODEL_MANIFEST_DIGEST}
release_apk_sha256=$(sha256 "${release_apk}")
release_apk_size=$(size_bytes "${release_apk}")
release_aab_sha256=$(sha256 "${release_aab}")
release_aab_size=$(size_bytes "${release_aab}")
releaseqa_apk_sha256=$(sha256 "${releaseqa_apk}")
releaseqa_apk_size=$(size_bytes "${releaseqa_apk}")
production_signed=${production_signed}
production_cert_sha256=${production_cert_sha256}
EOF

cat "${out}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        echo "### Stage 14.5 Full Release provenance"
        echo
        echo '```text'
        cat "${out}"
        echo '```'
    } >> "${GITHUB_STEP_SUMMARY}"
fi
