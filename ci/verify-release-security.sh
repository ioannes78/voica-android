#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <apk>" >&2
    exit 2
fi

apk="$1"
analyzer="$(command -v apkanalyzer)"
test -f "${apk}"
test -n "${analyzer}"

manifest="$(mktemp)"
trap 'rm -f "${manifest}"' EXIT
"${analyzer}" manifest print "${apk}" > "${manifest}"

grep -q 'android:allowBackup="false"' "${manifest}"
grep -q 'android:usesCleartextTraffic="false"' "${manifest}"
grep -Fq 'android:dataExtractionRules="@xml/data_extraction_rules"' app/src/main/AndroidManifest.xml
grep -Fq 'android:fullBackupContent="@xml/backup_rules"' app/src/main/AndroidManifest.xml

model_manager="app/src/main/java/io/github/ioannes78/voica/AppModelManager.kt"
grep -Fq 'PRODUCTION_MODEL_CHANNEL_COMMIT' "${model_manager}"
grep -Fq 'eeb59214244f392f3e2eecdb90016efbe3a95401' "${model_manager}"
grep -Fq 'PRODUCTION_MANIFEST_VERSION = 9' "${model_manager}"
grep -Fq 'a49da9fdf2dabf2408ba2bb14cf6b5c25c35563052cc6307db8f8095caac0b45' "${model_manager}"

if grep -Fq 'voica-model-channel/main/manifests/production.json' "${model_manager}"; then
    echo "Production model catalog must not track mutable main." >&2
    exit 1
fi

echo "Verified security/model-channel boundary for ${apk}"
