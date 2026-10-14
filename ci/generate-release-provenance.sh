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

test -f "${release_apk}"
test -f "${release_aab}"
if [[ "${releaseqa_apk}" != "-" ]]; then
    test -f "${releaseqa_apk}"
fi

mkdir -p "$(dirname "${out}")"

sha256() {
    sha256sum "$1" | awk '{print $1}'
}
size_bytes() {
    stat -c %s "$1"
}

releaseqa_sha256="N/A"
releaseqa_size="N/A"
if [[ "${releaseqa_apk}" != "-" ]]; then
    releaseqa_sha256="$(sha256 "${releaseqa_apk}")"
    releaseqa_size="$(size_bytes "${releaseqa_apk}")"
fi

source_head_sha="${VOICA_SOURCE_HEAD_SHA:-${GITHUB_SHA:-unknown}}"
workflow_build_sha="${GITHUB_SHA:-unknown}"

database_file="core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"
test -f "${database_file}"
room_schema="$(
    python3 - "${database_file}" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text()
database = re.search(r"@Database\s*\((.*?)\)\s*abstract\s+class\s+VoicaDatabase", text, re.S)
if not database:
    raise SystemExit("Unable to locate VoicaDatabase @Database declaration")
version = re.search(r"\bversion\s*=\s*(\d+)", database.group(1))
if not version:
    raise SystemExit("Unable to derive current Room schema version")
print(version.group(1))
PY
)"

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
room_schema=${room_schema}
abi=arm64-v8a
production_model_channel_commit=${VOICA_MODEL_CHANNEL_COMMIT}
production_model_manifest_version=${VOICA_MODEL_MANIFEST_VERSION}
production_model_manifest_digest=${VOICA_MODEL_MANIFEST_DIGEST}
release_apk_sha256=$(sha256 "${release_apk}")
release_apk_size=$(size_bytes "${release_apk}")
release_aab_sha256=$(sha256 "${release_aab}")
release_aab_size=$(size_bytes "${release_aab}")
releaseqa_apk_sha256=${releaseqa_sha256}
releaseqa_apk_size=${releaseqa_size}
production_signed=${production_signed}
production_cert_sha256=${production_cert_sha256}
EOF

cat "${out}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        echo "### Release provenance"
        echo
        echo '```text'
        cat "${out}"
        echo '```'
    } >> "${GITHUB_STEP_SUMMARY}"
fi
