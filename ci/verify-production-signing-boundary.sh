#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <release-apk>" >&2
    exit 2
fi

release_apk="$1"
test -f "${release_apk}"

signing_values=(
    "${VOICA_RELEASE_STORE_FILE:-}"
    "${VOICA_RELEASE_STORE_PASSWORD:-}"
    "${VOICA_RELEASE_KEY_ALIAS:-}"
    "${VOICA_RELEASE_KEY_PASSWORD:-}"
)
configured=0
for value in "${signing_values[@]}"; do
    [[ -n "${value}" ]] && configured=$((configured + 1))
done

if (( configured > 0 && configured < 4 )); then
    echo "Production signing is partially configured." >&2
    exit 1
fi

signer="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/build-tools/36.0.0/apksigner"
test -x "${signer}"

if (( configured == 0 )); then
    if [[ -n "${VOICA_RELEASE_CERT_SHA256:-}" ]]; then
        echo "VOICA_RELEASE_CERT_SHA256 must not be supplied without a complete production signer." >&2
        exit 1
    fi
    if "${signer}" verify "${release_apk}" >/dev/null 2>&1; then
        echo "Release APK unexpectedly contains a signer while production credentials are absent." >&2
        exit 1
    fi
    echo "Production signing boundary verified: signer not provisioned; Release APK remains unsigned."
    exit 0
fi

if [[ -z "${VOICA_RELEASE_CERT_SHA256:-}" ]]; then
    echo "A provisioned production signer requires VOICA_RELEASE_CERT_SHA256." >&2
    exit 1
fi

expected_cert_sha256="$(printf '%s' "${VOICA_RELEASE_CERT_SHA256}" | tr '[:upper:]' '[:lower:]' | tr -d ':')"
actual_cert_sha256="$(
    "${signer}" verify --print-certs "${release_apk}" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
        tr '[:upper:]' '[:lower:]'
)"

test -n "${actual_cert_sha256}"
test "${actual_cert_sha256}" = "${expected_cert_sha256}"
echo "Production signing boundary verified against pinned certificate SHA-256."
