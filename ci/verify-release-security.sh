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
grep -Fq '8f2f94ef7a376f1b0b652072ef67df5e07dd31de' "${model_manager}"
grep -Fq 'PRODUCTION_MANIFEST_VERSION = 8' "${model_manager}"
grep -Fq '05dcd9f205a3383c2bda0704996b85e741384daa9c2e8a63ff132b74b5424d37' "${model_manager}"

if grep -Fq 'voica-model-channel/main/manifests/production.json' "${model_manager}"; then
    echo "Production model catalog must not track mutable main." >&2
    exit 1
fi

echo "Verified security/model-channel boundary for ${apk}"
