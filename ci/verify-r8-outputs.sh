#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <release|releaseQa>" >&2
    exit 2
fi

variant="$1"
mapping_dir="app/build/outputs/mapping/${variant}"

test -s "${mapping_dir}/mapping.txt"
test -s "${mapping_dir}/configuration.txt"
test -f "${mapping_dir}/seeds.txt"
test -f "${mapping_dir}/usage.txt"

echo "Verified R8 outputs for ${variant}."
