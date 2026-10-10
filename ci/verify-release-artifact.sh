#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
    echo "Usage: $0 <apk> <application-id> <version-code> <version-name> <qa|unsigned|production>" >&2
    exit 2
fi

apk="$1"
expected_application_id="$2"
expected_version_code="$3"
expected_version_name="$4"
signing_mode="$5"

analyzer="$(command -v apkanalyzer)"
signer="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}/build-tools/36.0.0/apksigner"

test -f "${apk}"
test -n "${analyzer}"
test -x "${signer}"

test "$("${analyzer}" manifest application-id "${apk}")" = "${expected_application_id}"
test "$("${analyzer}" manifest version-code "${apk}")" = "${expected_version_code}"
test "$("${analyzer}" manifest version-name "${apk}")" = "${expected_version_name}"

manifest="$(mktemp)"
trap 'rm -f "${manifest}"' EXIT
"${analyzer}" manifest print "${apk}" > "${manifest}"

if grep -q 'android:debuggable="true"' "${manifest}"; then
    echo "APK must not be debuggable: ${apk}" >&2
    exit 1
fi

grep -q 'android:allowBackup="false"' "${manifest}"
grep -q 'android:usesCleartextTraffic="false"' "${manifest}"

read_apk_cert_sha256() {
    "${signer}" verify --print-certs "${apk}" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
        head -n 1 |
        tr '[:upper:]' '[:lower:]' |
        tr -d ':[:space:]'
}

case "${signing_mode}" in
    qa)
        expected_cert_sha256="3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288"
        actual_cert_sha256="$(read_apk_cert_sha256)"
        test "${actual_cert_sha256}" = "${expected_cert_sha256}"
        ;;
    unsigned)
        if "${signer}" verify "${apk}" >/dev/null 2>&1; then
            echo "Unsigned Release boundary violated: ${apk} unexpectedly contains a valid signer." >&2
            exit 1
        fi
        ;;
    production)
        expected_cert_sha256="${VOICA_EXPECTED_PRODUCTION_CERT_SHA256:-}"
        if [[ -z "${expected_cert_sha256}" ]]; then
            echo "VOICA_EXPECTED_PRODUCTION_CERT_SHA256 is required for production verification." >&2
            exit 2
        fi
        expected_cert_sha256="$(
            printf '%s' "${expected_cert_sha256}" |
                tr '[:upper:]' '[:lower:]' |
                tr -d ':[:space:]'
        )"
        actual_cert_sha256="$(read_apk_cert_sha256)"
        if [[ -z "${actual_cert_sha256}" ]]; then
            echo "Production APK signer certificate digest is missing." >&2
            exit 1
        fi
        if [[ "${actual_cert_sha256}" = "3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288" ]]; then
            echo "Production APK must not use the public QA signer." >&2
            exit 1
        fi
        test "${actual_cert_sha256}" = "${expected_cert_sha256}"
        ;;
    *)
        echo "Unknown signing mode: ${signing_mode}" >&2
        exit 2
        ;;
esac

echo "Verified ${apk}: ${expected_application_id} ${expected_version_name} (${expected_version_code}), signing=${signing_mode}"
