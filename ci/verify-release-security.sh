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
grep -Fq '6aa111fd973f87254ac7edbcae7260ac17d4b5d1' "${model_manager}"
grep -Fq 'PRODUCTION_MANIFEST_VERSION = 10' "${model_manager}"
grep -Fq '2dda741fa038ad37e20b9f9750de9acce1d7aa0c8dab246854de69bd8e0ef120' "${model_manager}"

sherpa_gradle="engine/sherpa/build.gradle.kts"
grep -Fq 'v1.13.8-p5-ctc-r2' "${sherpa_gradle}"
grep -Fq 'dc8772134c93fff4b5ee68d18f085d73b78a1891cf6b7056d8d8a893dfa4370e' "${sherpa_gradle}"
if grep -Fq '?: "v1.13.8"' "${sherpa_gradle}"; then
    echo "Stock Sherpa fallback is forbidden after FunASR revision-2 production promotion." >&2
    exit 1
fi

if grep -Fq 'voica-model-channel/main/manifests/production.json' "${model_manager}"; then
    echo "Production model catalog must not track mutable main." >&2
    exit 1
fi

echo "Verified security/model-channel/FunASR-r2 runtime boundary for ${apk}"
