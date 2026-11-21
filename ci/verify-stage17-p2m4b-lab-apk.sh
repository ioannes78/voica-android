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

# Validate the *merged* manifest structurally. Android tooling may leave
# process suffixes relative (":...") rather than expanding the package name.
python3 - "$manifest" "$package" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path, package = sys.argv[1:]
android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(manifest_path).getroot()
app = root.find("application")
assert app is not None, "Missing merged application"
assert app.get(android + "allowBackup") == "false", "Backup must be disabled"
assert app.get(android + "usesCleartextTraffic") == "false", "Cleartext traffic must be disabled"

components = {}
launchers = []
for kind in ("activity", "activity-alias", "service"):
    for item in app.findall(kind):
        name = item.get(android + "name", "")
        components[name] = (kind, item)
        for intent in item.findall("intent-filter"):
            actions = {e.get(android + "name") for e in intent.findall("action")}
            categories = {e.get(android + "name") for e in intent.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launchers.append(name)

class_namespace = "io.github.ioannes78.voica"
read_only = class_namespace + ".Stage17P2M4BReadOnlyActivity"
assert launchers == [read_only], f"Wrong Lab launcher(s): {launchers}"
assert read_only in components, "Read-only diagnostics not registered"
assert components[read_only][1].get(android + "exported") == "true"
assert not any(n.endswith(".MainActivity") for n in components), "Product MainActivity must be absent"

for service_name, suffix in (
    ("Stage17P2M4BParentService", ":stage17_m4b_parent"),
    ("Stage17P2M4BChildService", ":stage17_m4b_child"),
):
    fqname = class_namespace + "." + service_name
    assert fqname in components, f"Missing Lab fixture {service_name}"
    kind, item = components[fqname]
    assert kind == "service"
    assert item.get(android + "exported") == "false", f"Exported fixture: {service_name}"
    observed = item.get(android + "process")
    assert observed in (suffix, package + suffix), f"Bad Lab process {service_name}: {observed}"
print("P2-M4B1 merged Lab manifest structural policy: PASS")
PY

# QA-only public certificate; no production signer, no real-process fault tool.
certificate="$("$signer" verify --print-certs "$apk" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 |
    tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')"
test "$certificate" = "3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288"

echo "P2-M4B1 isolated lab APK manifest / signer / launcher identity: PASS (NO UPLOAD)"
