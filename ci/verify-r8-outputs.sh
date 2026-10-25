#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <release|releaseQa>" >&2
    exit 2
fi

variant="$1"
mapping_dir="app/build/outputs/mapping/${variant}"

case "$variant" in
    release)
        apk="app/build/outputs/apk/release/app-release.apk"
        ;;
    releaseQa)
        apk="app/build/outputs/apk/releaseQa/app-releaseQa.apk"
        ;;
    *)
        echo "Unsupported variant: $variant" >&2
        exit 2
        ;;
esac

test -s "${mapping_dir}/mapping.txt"
test -s "${mapping_dir}/configuration.txt"
test -f "${mapping_dir}/seeds.txt"
test -f "${mapping_dir}/usage.txt"
test -s "$apk"

bash ci/verify-litertlm-r8-boundary.sh "$variant" "$apk"

echo "Verified R8 outputs for ${variant}."
