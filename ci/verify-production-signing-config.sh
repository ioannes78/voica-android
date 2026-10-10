#!/usr/bin/env bash
set -euo pipefail

if gradle :app:verifyProductionSigningConfiguration --no-configuration-cache >/tmp/no-production-signing.log 2>&1; then
    echo "Production signing gate unexpectedly passed without production credentials." >&2
    exit 1
fi
grep -q "Production signing is not configured" /tmp/no-production-signing.log

if VOICA_RELEASE_KEY_ALIAS="partial-only" \
    gradle :app:tasks --no-configuration-cache >/tmp/partial-production-signing.log 2>&1; then
    echo "Partial production signing configuration unexpectedly passed." >&2
    exit 1
fi
grep -q "Production signing is partially configured" /tmp/partial-production-signing.log

if VOICA_RELEASE_STORE_FILE="$PWD/ci/voica-qa.jks" \
    VOICA_RELEASE_STORE_PASSWORD="voica-qa-test" \
    VOICA_RELEASE_KEY_ALIAS="not-qa-alias" \
    VOICA_RELEASE_KEY_PASSWORD="voica-qa-test" \
    gradle :app:tasks --no-configuration-cache >/tmp/qa-store-as-production.log 2>&1; then
    echo "Public QA keystore unexpectedly passed as a production signer." >&2
    exit 1
fi
grep -q "public QA keystore" /tmp/qa-store-as-production.log

if VOICA_RELEASE_STORE_FILE="${RUNNER_TEMP:-/tmp}/nonexistent-production.jks" \
    VOICA_RELEASE_STORE_PASSWORD="placeholder" \
    VOICA_RELEASE_KEY_ALIAS="voica-qa" \
    VOICA_RELEASE_KEY_PASSWORD="placeholder" \
    gradle :app:tasks --no-configuration-cache >/tmp/qa-alias-as-production.log 2>&1; then
    echo "Public QA alias unexpectedly passed as a production signer." >&2
    exit 1
fi
grep -q "public QA key alias" /tmp/qa-alias-as-production.log

echo "Verified production signing configuration guardrails."
