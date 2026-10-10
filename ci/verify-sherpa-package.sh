#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <apk|aab> <artifact-path>" >&2
    exit 2
fi

kind="$1"
artifact="$2"
test -f "${artifact}"

grep -Fq -- '-keep,allowoptimization class com.k2fsa.sherpa.onnx.** {' app/proguard-rules.pro

listing="$(mktemp)"
jni_so="$(mktemp)"
dex_tree="$(mktemp)"
trap 'rm -f "${listing}" "${jni_so}" "${dex_tree}"' EXIT
zipinfo -1 "${artifact}" > "${listing}"

case "${kind}" in
    apk)
        required_prefix='lib/arm64-v8a/'
        forbidden_regex='(^|/)libsherpa-onnx-(c-api|cxx-api)\.so$'
        grep -qx "${required_prefix}libsherpa-onnx-jni.so" "${listing}"
        grep -qx "${required_prefix}libonnxruntime.so" "${listing}"
        if grep -Eq "${forbidden_regex}" "${listing}"; then
            echo "Unused Sherpa standalone C/C++ API library is packaged in ${artifact}." >&2
            exit 1
        fi
        if grep -E '^lib/' "${listing}" | grep -Ev '^lib/arm64-v8a/' >/dev/null; then
            echo "Unexpected non-arm64 native ABI packaged in ${artifact}." >&2
            grep -E '^lib/' "${listing}" | grep -Ev '^lib/arm64-v8a/' >&2 || true
            exit 1
        fi

        unzip -p "${artifact}" "${required_prefix}libsherpa-onnx-jni.so" > "${jni_so}"
        test -s "${jni_so}"
        if readelf -d "${jni_so}" | grep -Eq 'libsherpa-onnx-(c-api|cxx-api)\.so'; then
            echo "Sherpa JNI unexpectedly DT_NEEDED a trimmed standalone API library." >&2
            readelf -d "${jni_so}" | grep NEEDED >&2 || true
            exit 1
        fi

        analyzer="$(command -v apkanalyzer)"
        test -n "${analyzer}"
        "${analyzer}" dex packages --defined-only "${artifact}" > "${dex_tree}"
        for class_name in \
            'com.k2fsa.sherpa.onnx.OfflineRecognizer' \
            'com.k2fsa.sherpa.onnx.OfflineRecognizerResult' \
            'com.k2fsa.sherpa.onnx.Vad' \
            'com.k2fsa.sherpa.onnx.SpeechSegment' \
            'com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization' \
            'com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment'; do
            if ! awk -v expected="${class_name}" '$1 == "C" && $NF == expected { found=1 } END { exit found ? 0 : 1 }' "${dex_tree}"; then
                echo "Sherpa JNI ABI class missing or renamed after R8: ${class_name}" >&2
                exit 1
            fi
        done
        ;;
    aab)
        required_prefix='base/lib/arm64-v8a/'
        grep -qx 'base/manifest/AndroidManifest.xml' "${listing}"
        grep -qx "${required_prefix}libsherpa-onnx-jni.so" "${listing}"
        grep -qx "${required_prefix}libonnxruntime.so" "${listing}"
        if grep -Eq '(^|/)libsherpa-onnx-(c-api|cxx-api)\.so$' "${listing}"; then
            echo "Unused Sherpa standalone C/C++ API library is packaged in ${artifact}." >&2
            exit 1
        fi
        if grep -E '^base/lib/' "${listing}" | grep -Ev '^base/lib/arm64-v8a/' >/dev/null; then
            echo "Unexpected non-arm64 native ABI packaged in ${artifact}." >&2
            grep -E '^base/lib/' "${listing}" | grep -Ev '^base/lib/arm64-v8a/' >&2 || true
            exit 1
        fi
        unzip -tq "${artifact}" >/dev/null
        ;;
    *)
        echo "Unknown artifact kind: ${kind}" >&2
        exit 2
        ;;
esac

echo "Verified Sherpa/R8/native package boundary for ${artifact}"
