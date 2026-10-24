#!/usr/bin/env bash
set -euo pipefail

runtime_gradle="engine/sherpa/build.gradle.kts"
settings="settings.gradle.kts"
release_tag="runtime-sherpa-funasr-ctc-r2"
asset="sherpa-onnx-v1.13.8-p5-ctc-r2.aar"
expected_sha256="dc8772134c93fff4b5ee68d18f085d73b78a1891cf6b7056d8d8a893dfa4370e"
url="https://github.com/ioannes78/voica-android/releases/download/${release_tag}/${asset}"

grep -Fq 'v1.13.8-p5-ctc-r2' "$runtime_gradle"
grep -Fq "$expected_sha256" "$runtime_gradle"
grep -Fq "$release_tag" "$settings"
if grep -Fq '?: "v1.13.8"' "$runtime_gradle"; then
    echo "Stock Sherpa fallback is forbidden after FunASR revision-2 production promotion." >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --location --retry 4 --retry-all-errors --silent --show-error \
    "$url" -o "$work/$asset"
test -s "$work/$asset"
echo "$expected_sha256  $work/$asset" | sha256sum -c -

echo "Verified immutable production Sherpa FunASR CTC r2 runtime: $expected_sha256"
