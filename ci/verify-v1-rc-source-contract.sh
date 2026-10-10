#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

expected_version_code="${VOICA_VERSION_CODE:-87}"
expected_release_version="${VOICA_RELEASE_VERSION:-1.0.0-rc3-r2}"
expected_releaseqa_version="${VOICA_RELEASEQA_VERSION:-1.0.0-rc3-r2-export-qa}"
expected_model_commit="${VOICA_MODEL_CHANNEL_COMMIT:-be74c7065ce22a5f9b207a7cf1c88d3be0e872ec}"
expected_manifest_version="${VOICA_MODEL_MANIFEST_VERSION:-7}"
expected_manifest_digest="${VOICA_MODEL_MANIFEST_DIGEST:-8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf}"

fail() {
    echo "V1 RC source contract failed: $*" >&2
    exit 1
}

app_gradle="app/build.gradle.kts"
model_manager="app/src/main/java/io/github/ioannes78/voica/AppModelManager.kt"
schema_dir="core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase"

[[ -f "$app_gradle" ]] || fail "missing $app_gradle"
[[ -f "$model_manager" ]] || fail "missing $model_manager"

grep -Fq "versionCode = ${expected_version_code}" "$app_gradle" || fail "versionCode drift"
grep -Fq "versionName = \"${expected_release_version}\"" "$app_gradle" || fail "Release versionName drift"
grep -Fq 'versionNameSuffix = "-export-qa"' "$app_gradle" || fail "releaseQa suffix drift"
grep -Fq 'isDebuggable = false' "$app_gradle" || fail "Release must remain non-debuggable"
grep -Fq 'isMinifyEnabled = true' "$app_gradle" || fail "R8 must remain enabled"
grep -Fq 'isShrinkResources = true' "$app_gradle" || fail "resource shrinking must remain enabled"
grep -Fq 'abiFilters += "arm64-v8a"' "$app_gradle" || fail "V1 ABI contract drift"

for version in $(seq 1 12); do
    [[ -f "$schema_dir/${version}.json" ]] || fail "missing Room schema ${version}.json"
done
while IFS= read -r schema; do
    name="$(basename "$schema" .json)"
    if [[ "$name" =~ ^[0-9]+$ ]] && (( 10#$name > 12 )); then
        fail "unexpected Room schema beyond frozen v12: ${name}.json"
    fi
done < <(find "$schema_dir" -maxdepth 1 -type f -name '*.json' -print)

grep -Fq "$expected_model_commit" "$model_manager" || fail "production model-channel commit drift"
grep -Fq "PRODUCTION_MANIFEST_VERSION = ${expected_manifest_version}" "$model_manager" || fail "production manifest version drift"
grep -Fq "$expected_manifest_digest" "$model_manager" || fail "production manifest digest drift"
if grep -Fq 'voica-model-channel/main/manifests/production.json' "$model_manager"; then
    fail "production model catalog tracks mutable main"
fi

required_records=(
    docs/STAGE_13B_FREEZE.md
    docs/STAGE_13B_HANDOFF.md
    docs/STAGE_13C_FREEZE.md
    docs/STAGE_13C_HANDOFF.md
    docs/STAGE_14_3_R8_SIZE.md
    docs/STAGE_14_4_SECURITY_PRIVACY.md
    docs/STAGE_14_4_1_EXPORT_UX.md
    docs/STAGE_14_5_RELEASE_CI.md
)
for record in "${required_records[@]}"; do
    [[ -s "$record" ]] || fail "missing release evidence record: $record"
done

grep -Fq 'Room schema: `12`' docs/STAGE_14_5_RELEASE_CI.md || fail "Stage 14.5 Room baseline drift"
grep -Fq "Release versionName: \`${expected_release_version}\`" docs/STAGE_14_5_RELEASE_CI.md || fail "Stage 14.5 release identity drift"
grep -Fq "production model-channel commit: \`${expected_model_commit}\`" docs/STAGE_14_5_RELEASE_CI.md || fail "Stage 14.5 model provenance drift"

echo "Verified V1 RC source contract: code=${expected_version_code}, release=${expected_release_version}, releaseQa=${expected_releaseqa_version}, Room=v12."
echo "Manual Stage 14 Final Freeze evidence remains separate: signed production artifact, explicit RC real-device acceptance, and auditable 30/60/120-minute real-device stability evidence."
