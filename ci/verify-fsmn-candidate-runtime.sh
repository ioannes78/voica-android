#!/usr/bin/env bash
set -euo pipefail

variant="${1:-debug}"
module="engine/fsmn"
aar="${module}/build/outputs/aar/fsmn-${variant}.aar"
expected_so="jni/arm64-v8a/libvoica_fsmn_candidate_jni.so"

if [[ ! -f "${aar}" ]]; then
  echo "::error::Missing FSMN candidate AAR: ${aar}"
  exit 1
fi

listing="$(mktemp)"
so_file="$(mktemp)"
trap 'rm -f "${listing}" "${so_file}"' EXIT

unzip -Z1 "${aar}" > "${listing}"

grep -qx "${expected_so}" "${listing}" || {
  echo "::error::FSMN candidate JNI is missing from ${aar}"
  exit 1
}

if grep -Eq '(^|/)libonnxruntime\.so$' "${listing}"; then
  echo "::error::FSMN candidate AAR must not package a second libonnxruntime.so"
  exit 1
fi

# Only native library files define packaged ABIs. Directory entries such as `jni/`
# are metadata and must not be interpreted as an extra ABI.
if grep -E '^jni/.+\.so$' "${listing}" | grep -Ev '^jni/arm64-v8a/' >/dev/null; then
  echo "::error::FSMN candidate AAR contains a non-arm64 native ABI"
  grep -E '^jni/.+\.so$' "${listing}" >&2
  exit 1
fi

unzip -p "${aar}" "${expected_so}" > "${so_file}"

if readelf -d "${so_file}" | grep -F 'Shared library: [libonnxruntime.so]' >/dev/null; then
  echo "::error::FSMN candidate JNI must resolve the existing APK ONNX Runtime dynamically, not DT_NEEDED-link it"
  exit 1
fi

size_bytes="$(wc -c < "${so_file}")"
if (( size_bytes > 1048576 )); then
  echo "::error::FSMN runtime probe unexpectedly exceeds 1 MiB (${size_bytes} bytes)"
  exit 1
fi

echo "Verified FSMN candidate runtime boundary: arm64-only, no packaged/direct-linked ONNX Runtime, JNI=${size_bytes} bytes."
