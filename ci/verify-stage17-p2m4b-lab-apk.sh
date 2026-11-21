#!/usr/bin/env bash
set -euo pipefail

# P2-M4B1: CI-only structural verification. Never install, publish, or run a
# service from this APK. m4bLab uses a different package from historical .qa.
apk="app/build/outputs/apk/m4bLab/app-m4bLab.apk"
package="io.github.ioannes78.voica.m4blab"
test -s "$apk"

analyzer="$(command -v apkanalyzer)"
signer="${ANDROID_SDK_ROOT:?}/build-tools/36.0.0/apksigner"
test -x "$signer"

test "$("$analyzer" manifest application-id "$apk")" = "$package"
version_code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts | head -n1)"
version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -n1)"
test -n "$version_code"
test -n "$version_name"
test "$("$analyzer" manifest version-code "$apk")" = "$version_code"
test "$("$analyzer" manifest version-name "$apk")" = "${version_name}-m4b-lab"

manifest="$(mktemp)"
trap 'rm -f "$manifest"' EXIT
"$analyzer" manifest print "$apk" > "$manifest"

# Single readonly launcher; full product MainActivity is removed.
grep -Fq 'Stage17P2M4BReadOnlyActivity' "$manifest"
grep -Fq 'android.intent.action.MAIN' "$manifest"
test "$(grep -Fc 'android.intent.category.LAUNCHER' "$manifest")" -eq 1
if grep -Fq 'io.github.ioannes78.voica.MainActivity' "$manifest"; then
  echo "::error::m4bLab still includes production MainActivity"
  exit 1
fi

# Distinct, non-exported Binder fixtures; never a production package or process.
for component in Stage17P2M4BParentService Stage17P2M4BChildService; do
  grep -Fq "$component" "$manifest"
done
grep -Fq "${package}:stage17_m4b_parent" "$manifest"
grep -Fq "${package}:stage17_m4b_child" "$manifest"
grep -Fq 'android:allowBackup="false"' "$manifest"
grep -Fq 'android:usesCleartextTraffic="false"' "$manifest"

# QA-only public certificate; no production signer, no real-process fault tool.
certificate="$("$signer" verify --print-certs "$apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 |
    tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
test "$certificate" = "3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288"

echo "P2-M4B1 isolated lab APK manifest / signer / launcher identity: PASS (NO UPLOAD)"
