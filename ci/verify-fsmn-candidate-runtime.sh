#!/usr/bin/env bash
set -euo pipefail

variant="${1:-debug}"
module="engine/fsmn"
aar="${module}/build/outputs/aar/fsmn-${variant}.aar"
expected_so="jni/arm64-v8a/libvoica_fsmn_candidate_jni.so"
endpoint_source="${module}/src/main/cpp/voica_fsmn_runtime_probe.cpp"

if [[ ! -f "${aar}" ]]; then
  echo "::error::Missing FSMN candidate AAR: ${aar}"
  exit 1
fi

# Gate A freezes the first reproducible FSMN endpoint baseline to the pinned
# ONNX model semantics. Keep these values explicit and reject the earlier
# dynamic 2000→100 ms schedule so benchmark results remain interpretable.
grep -Fq 'constexpr int kGateAMaxEndSilenceMs = 800;' "${endpoint_source}" || {
  echo "::error::FSMN Gate A max_end_silence_time must remain 800 ms"
  exit 1
}
grep -Fq 'constexpr int kGateASpeechToSilenceMs = 150;' "${endpoint_source}" || {
  echo "::error::FSMN Gate A speech_to_silence must remain 150 ms"
  exit 1
}
grep -Fq 'constexpr int kGateALookaheadEndMs = 100;' "${endpoint_source}" || {
  echo "::error::FSMN Gate A lookahead_end must remain 100 ms"
  exit 1
}
if grep -Eq 'kScheduleChunkFrames|accumulated_ms_|in_speech_latched_|scheduled_ms' "${endpoint_source}"; then
  echo "::error::FSMN Gate A baseline must not contain the prior dynamic silence schedule"
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

echo "Verified FSMN Gate A baseline: max_end_silence=800 ms, speech_to_silence=150 ms, lookahead_end=100 ms."
echo "Verified FSMN candidate runtime boundary: arm64-only, no packaged/direct-linked ONNX Runtime, JNI=${size_bytes} bytes."
