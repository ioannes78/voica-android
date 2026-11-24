#!/usr/bin/env bash
# Non-destructive, API31 GitHub-hosted AVD evidence collection only.
# Deliberately NO owner enrollment, Binder admission, activity launch or kill.
set -euo pipefail
: "${LAB_APK:?missing LAB_APK}"
: "${EVIDENCE:?missing EVIDENCE}"
: "${VOICA_BUILD_GIT_SHA:?missing exact source SHA}"
mkdir -p "$EVIDENCE"

# Resolve Android SDK tools explicitly; do not rely on the default PATH.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?missing Android SDK root}"
mkdir -p "$EVIDENCE"

# Capture host hypervisor and ADB state before waiting for the new emulator.
{
  echo "observed_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "runner_os=$(uname -a)"
  "$sdk/emulator/emulator" -accel-check || true
  adb start-server || true
  adb devices -l || true
} > "$EVIDENCE/emulator-startup.txt" 2>&1

# The action normally waits for boot, but fail closed with a bounded readiness
# loop rather than treating an ADB transport in "offline" state as ready.
deadline=$((SECONDS + 240))
serial=""
while (( SECONDS < deadline )); do
  mapfile -t emulators < <(adb devices | awk 'NR>1 && $1 ~ /^emulator-/ && ($2=="device" || $2=="offline") {print $1}')
  if (( ${#emulators[@]} != 1 )); then
    if (( ${#emulators[@]} > 1 )); then
      echo "DENY: more than one emulator is attached" >&2
      adb devices -l >&2 || true
      exit 2
    fi
    sleep 5
    continue
  fi
  serial="${emulators[0]}"
  state="$(adb -s "$serial" get-state 2>/dev/null || true)"
  if [[ "$state" == "device" ]]; then
    boot="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == "1" ]]; then
      break
    fi
  fi
  sleep 5
done
if [[ -z "$serial" || "$(adb -s "$serial" get-state 2>/dev/null || true)" != "device" || "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != "1" ]]; then
  {
    echo "DENY: emulator did not become ADB-online with sys.boot_completed=1"
    adb devices -l || true
    adb -s "$serial" shell getprop sys.boot_completed || true
  } | tee "$EVIDENCE/emulator-readiness-failure.txt" >&2
  exit 2
fi

# Resolve build-tools executables explicitly; do not rely on the default PATH.
analyzer="$sdk/cmdline-tools/latest/bin/apkanalyzer"
if [[ ! -x "$analyzer" ]]; then
  analyzer="$sdk/tools/bin/apkanalyzer"
fi
signer="$sdk/build-tools/36.0.0/apksigner"
test -x "$analyzer" && test -x "$signer" || {
  echo "DENY: apkanalyzer/apksigner missing" >&2
  exit 2
}

# Strictly check fresh emulator state before any installation.
python3 ci/stage17_p2m4b_lab_preflight.py \
  --stage before --serial "$serial" --lab-apk "$LAB_APK" \
  --apkanalyzer "$analyzer" --apksigner "$signer" > "$EVIDENCE/before.json"

python3 - "$EVIDENCE" "$serial" "$VOICA_BUILD_GIT_SHA" <<'PY'
import json, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
folder, serial, sha = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def prop(key):
    return subprocess.check_output(
        ["adb", "-s", serial, "shell", "getprop", key], text=True, timeout=20
    ).strip()
evidence = {
    "schema": "P2_M4B1_CLOUD_AVD_OBSERVATION_V1",
    "observed_utc": datetime.now(timezone.utc).isoformat(),
    "source_sha": sha,
    "device_serial": serial,
    "android_api": prop("ro.build.version.sdk"),
    "android_fingerprint": prop("ro.build.fingerprint"),
    "device_model": prop("ro.product.model"),
    "emulator_observation_only": True,
    "snapshot_restore_requested": False,
    "independent_historical_attestation": False,
    "never_armed_proven": False,
    "owner_seed_authorized": False,
    "ipc_admission_authorized": False,
    "fault_injection_authorized": False,
}
if evidence["android_api"] != "31":
    raise SystemExit("DENY: unexpected Android API")
(folder / "environment.json").write_text(
    json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
)
PY

# ADB installs only the independently named .m4blab package.
# It never starts the app or triggers a Binder probe.
adb -s "$serial" install --no-streaming "$LAB_APK"
python3 ci/stage17_p2m4b_lab_preflight.py \
  --stage after --serial "$serial" --lab-apk "$LAB_APK" \
  --apkanalyzer "$analyzer" --apksigner "$signer" > "$EVIDENCE/after.json"
python3 ci/stage17_p2m4b_lab_correlate.py \
  --before "$EVIDENCE/before.json" --after "$EVIDENCE/after.json" \
  > "$EVIDENCE/correlation.json"

python3 - "$EVIDENCE" <<'PY'
import json, sys
from pathlib import Path
root = Path(sys.argv[1])
result = json.loads((root / "correlation.json").read_text(encoding="utf-8"))
if result.get("verdict") != "OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT":
    raise SystemExit("DENY: correlation did not fail closed")
(root / "VERDICT.txt").write_text(
    "PASS: API31 host installation observations correlated.\n"
    "NOT_AN_ENROLLMENT; NO_OWNER_ADMISSION; NO_CHILD_HANDOFF; NO_PROCESS_EXIT.\n"
    "B1-1/B1-2/B1-3/B1-4 NOT CLOSED.\n", encoding="utf-8"
)
PY
