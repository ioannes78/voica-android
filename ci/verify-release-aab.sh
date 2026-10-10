#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <aab> <application-id> <version-code> <version-name>" >&2
    exit 2
fi

aab="$1"
expected_application_id="$2"
expected_version_code="$3"
expected_version_name="$4"

test -f "${aab}"
unzip -tq "${aab}" >/dev/null

listing="$(mktemp)"
manifest="$(mktemp)"
trap 'rm -f "${listing}" "${manifest}"' EXIT
zipinfo -1 "${aab}" > "${listing}"
grep -qx 'base/manifest/AndroidManifest.xml' "${listing}"
grep -qx 'base/resources.pb' "${listing}"
unzip -p "${aab}" 'base/manifest/AndroidManifest.xml' > "${manifest}"
test -s "${manifest}"

# Bundle manifests are protobuf XML. Keep the identity contract explicit in source and
# also require the string payloads to be present in the generated base manifest.
strings "${manifest}" | grep -Fq "${expected_application_id}"
strings "${manifest}" | grep -Fq "${expected_version_name}"
grep -Fq "versionCode = ${expected_version_code}" app/build.gradle.kts
grep -Fq "versionName = \"${expected_version_name}\"" app/build.gradle.kts

echo "Verified Release AAB structure and identity contract: ${expected_application_id} ${expected_version_name} (${expected_version_code})"
