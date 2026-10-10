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
grep -Fq 'be74c7065ce22a5f9b207a7cf1c88d3be0e872ec' "${model_manager}"
grep -Fq 'PRODUCTION_MANIFEST_VERSION = 7' "${model_manager}"
grep -Fq '8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf' "${model_manager}"

if grep -Fq 'voica-model-channel/main/manifests/production.json' "${model_manager}"; then
    echo "Production model catalog must not track mutable main in V1." >&2
    exit 1
fi

echo "Verified Stage 14.4 security/model-channel boundary for ${apk}"
