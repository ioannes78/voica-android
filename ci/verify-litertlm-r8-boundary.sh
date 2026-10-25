#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <release|releaseQa> <apk>" >&2
    exit 2
fi

variant="$1"
apk="$2"
mapping_dir="app/build/outputs/mapping/${variant}"
configuration="${mapping_dir}/configuration.txt"

case "$variant" in
    release|releaseQa) ;;
    *)
        echo "Unsupported variant: $variant" >&2
        exit 2
        ;;
esac

test -s "$apk"
test -s "$configuration"

# Stage 17 P0 R7 established this full-package rule as a native/JNI ABI contract.
grep -Fq -- '-keep,allowoptimization class com.google.ai.edge.litertlm.** {' app/proguard-rules.pro
grep -Fq -- 'com.google.ai.edge.litertlm.**' "$configuration"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
dexdump_bin="$(command -v dexdump || true)"
if [[ -z "$dexdump_bin" && -n "$sdk_root" && -d "$sdk_root/build-tools" ]]; then
    dexdump_bin="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name dexdump -perm -111 -print | sort -V | tail -n1)"
fi
if [[ -z "$dexdump_bin" || ! -x "$dexdump_bin" ]]; then
    echo "::error::dexdump was not found in PATH or Android build-tools." >&2
    exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
all_dump="$tmp_dir/all-dexdump.txt"
class_dump="$tmp_dir/class-descriptors.txt"
: > "$all_dump"

dex_count=0
while IFS= read -r dex_entry; do
    [[ -n "$dex_entry" ]] || continue
    dex_count=$((dex_count + 1))
    dex_file="$tmp_dir/$(basename "$dex_entry")"
    unzip -p "$apk" "$dex_entry" > "$dex_file"
    "$dexdump_bin" "$dex_file" >> "$all_dump"
done < <(unzip -Z1 "$apk" | grep -E '^classes([0-9]+)?\.dex$')

if (( dex_count == 0 )); then
    echo "::error::No classes*.dex entries found in $apk" >&2
    exit 1
fi

grep -F 'Class descriptor' "$all_dump" > "$class_dump"

required_descriptors=(
    'Lcom/google/ai/edge/litertlm/Engine;'
    'Lcom/google/ai/edge/litertlm/Conversation;'
    'Lcom/google/ai/edge/litertlm/ConversationConfig;'
    'Lcom/google/ai/edge/litertlm/Content;'
    'Lcom/google/ai/edge/litertlm/Contents;'
    'Lcom/google/ai/edge/litertlm/ExperimentalFlags;'
    'Lcom/google/ai/edge/litertlm/LiteRtLmJni;'
)

for descriptor in "${required_descriptors[@]}"; do
    if ! grep -Fq "'$descriptor'" "$class_dump"; then
        echo "::error::LiteRT-LM R8/JNI boundary missing class descriptor: $descriptor" >&2
        exit 1
    fi
done

litert_class_count="$(grep -F "'Lcom/google/ai/edge/litertlm/" "$class_dump" | wc -l | tr -d '[:space:]')"
if [[ -z "$litert_class_count" ]]; then
    litert_class_count=0
fi
if (( litert_class_count < 100 )); then
    echo "::error::LiteRT-LM package retained only ${litert_class_count} class descriptors; P0 R7 baseline retained about 107." >&2
    exit 1
fi

echo "Verified LiteRT-LM R8/JNI boundary for ${variant}: ${litert_class_count} package class descriptors across ${dex_count} DEX files."
