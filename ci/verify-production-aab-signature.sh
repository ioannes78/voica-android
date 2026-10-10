#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <aab> <expected-cert-sha256>" >&2
    exit 2
fi

aab="$1"
expected_cert_sha256="$2"
qa_cert_sha256="3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288"

test -f "${aab}"

normalize_sha256() {
    tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]'
}

expected_cert_sha256="$(printf '%s' "${expected_cert_sha256}" | normalize_sha256)"
if [[ -z "${expected_cert_sha256}" ]]; then
    echo "Expected production certificate SHA-256 is empty." >&2
    exit 2
fi
if [[ "${expected_cert_sha256}" = "${qa_cert_sha256}" ]]; then
    echo "Production AAB must not use the public QA signer." >&2
    exit 1
fi

verify_log="$(mktemp)"
trap 'rm -f "${verify_log}"' EXIT
if ! jarsigner -verify -strict "${aab}" >"${verify_log}" 2>&1; then
    cat "${verify_log}" >&2
    exit 1
fi

actual_cert_sha256="$(
    LC_ALL=C keytool -printcert -jarfile "${aab}" 2>/dev/null |
        sed -n 's/^[[:space:]]*SHA256: //p' |
        head -n 1 |
        normalize_sha256
)"

if [[ -z "${actual_cert_sha256}" ]]; then
    echo "Unable to read the production AAB signer certificate." >&2
    exit 1
fi
if [[ "${actual_cert_sha256}" = "${qa_cert_sha256}" ]]; then
    echo "Production AAB unexpectedly uses the public QA signer." >&2
    exit 1
fi
test "${actual_cert_sha256}" = "${expected_cert_sha256}"

echo "Verified production AAB signature. certificate_sha256=${actual_cert_sha256}"
