#!/usr/bin/env bash
set -euo pipefail

SOURCE_URL="${SOURCE_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2}"
SOURCE_ASSET_ID="${SOURCE_ASSET_ID:-157661357}"
SOURCE_EXPECTED_BYTES="${SOURCE_EXPECTED_BYTES:-458187351}"
REVISION="${REVISION:-1}"
OUT_DIR="${OUT_DIR:-dist}"
PACKAGE_NAME="zipformer-small-bilingual-int8-2023-02-16-voica-r${REVISION}.zip"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$OUT_DIR" "$work/source" "$work/package"

archive="$work/source.tar.bz2"
curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
  "$SOURCE_URL" -o "$archive"

actual_bytes="$(stat -c '%s' "$archive")"
if [[ "$actual_bytes" != "$SOURCE_EXPECTED_BYTES" ]]; then
  echo "Unexpected upstream archive size: $actual_bytes (expected $SOURCE_EXPECTED_BYTES)" >&2
  exit 1
fi

tar -xjf "$archive" -C "$work/source"

copy_unique() {
  local pattern="$1"
  local target="$2"
  mapfile -t matches < <(find "$work/source" -type f -name "$pattern" -print)
  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "Expected exactly one $pattern, found ${#matches[@]}" >&2
    printf '%s\n' "${matches[@]}" >&2
    exit 1
  fi
  cp "${matches[0]}" "$work/package/$target"
}

copy_unique 'encoder-epoch-99-avg-1.int8.onnx' 'encoder.int8.onnx'
copy_unique 'decoder-epoch-99-avg-1.int8.onnx' 'decoder.int8.onnx'
copy_unique 'joiner-epoch-99-avg-1.int8.onnx' 'joiner.int8.onnx'
copy_unique 'tokens.txt' 'tokens.txt'

for file in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  test -s "$work/package/$file"
  touch -t 198001010000 "$work/package/$file"
done

(
  cd "$work/package"
  zip -X -9 "$OLDPWD/$OUT_DIR/$PACKAGE_NAME" \
    encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt
)

source_sha="$(sha256sum "$archive" | awk '{print $1}')"
package_path="$OUT_DIR/$PACKAGE_NAME"
package_sha="$(sha256sum "$package_path" | awk '{print $1}')"
package_bytes="$(stat -c '%s' "$package_path")"

python3 - "$work/package" "$OUT_DIR/candidate-small-bilingual.json" \
  "$REVISION" "$PACKAGE_NAME" "$package_sha" "$package_bytes" \
  "$SOURCE_URL" "$SOURCE_ASSET_ID" "$SOURCE_EXPECTED_BYTES" "$source_sha" <<'PY'
import hashlib
import json
import os
import sys

root, out, revision, package_name, package_sha, package_bytes, source_url, source_asset_id, source_bytes, source_sha = sys.argv[1:]
files = []
for name in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
    path = os.path.join(root, name)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    files.append({
        "relativePath": name,
        "sizeBytes": os.path.getsize(path),
        "sha256": h.hexdigest(),
        "packagePath": name,
    })

payload = {
    "modelId": "zipformer-small-bilingual",
    "kind": "ASR_STREAMING",
    "displayName": "Small Bilingual Zipformer int8",
    "version": "2023-02-16",
    "revision": int(revision),
    "runtimeId": "sherpa-onnx",
    "runtimeVersionMin": "1.13.8",
    "languages": ["zh", "en"],
    "package": {
        "name": package_name,
        "sizeBytes": int(package_bytes),
        "sha256": package_sha,
        "format": "ZIP",
    },
    "files": files,
    "source": {
        "url": source_url,
        "githubReleaseAssetId": int(source_asset_id),
        "expectedBytes": int(source_bytes),
        "observedSha256": source_sha,
        "license": "Apache-2.0",
    },
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

echo "Package: $package_path"
echo "Package SHA-256: $package_sha"
echo "Package bytes: $package_bytes"
echo "Candidate metadata: $OUT_DIR/candidate-small-bilingual.json"
