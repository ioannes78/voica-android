# Stage 14.2 — Production Release Build / Signing / Versioning

Status: implementation candidate; not a Final Freeze/Handoff document.

## Scope

Stage 14.2 establishes the V1 release build identity and production-signing boundary without enabling R8/resource shrinking yet. Stage 14.3 owns shrinking/package-size optimization.

No Room schema change and no production model-channel change are part of this substage.

## V1 RC identity

Current Stage 14.2 identity:

- applicationId: `io.github.ioannes78.voica`
- versionCode: `81`
- versionName: `1.0.0-rc1`
- ABI: `arm64-v8a`
- Room: schema 12

Build-type identity:

| Build type | Application ID | Version name | Signing role |
| --- | --- | --- | --- |
| Debug | `io.github.ioannes78.voica` | `1.0.0-rc1` | development only |
| QA | `io.github.ioannes78.voica.qa` | `1.0.0-rc1-qa` | public test-only QA key |
| Release | `io.github.ioannes78.voica` | `1.0.0-rc1` | production key only |

`1.0.0-rc1` is an internal release-candidate identity. The final Stage 14 release target remains `1.0.0` after all later release gates and real-device RC validation pass.

## Production signing inputs

Production signing must never reuse `ci/voica-qa.jks` or the `voica-qa` alias.

The Android build accepts a complete production signing configuration from environment variables:

- `VOICA_RELEASE_STORE_FILE`
- `VOICA_RELEASE_STORE_PASSWORD`
- `VOICA_RELEASE_KEY_ALIAS`
- `VOICA_RELEASE_KEY_PASSWORD`

Matching Gradle-property fallbacks are supported for secure local environments:

- `voica.release.storeFile`
- `voica.release.storePassword`
- `voica.release.keyAlias`
- `voica.release.keyPassword`

All four values must be present or all four absent. A partial configuration fails Gradle configuration instead of silently creating an incorrectly signed build.

The configured production keystore path is also rejected if it resolves to the public QA keystore, and the public QA alias is rejected for production.

The task:

`verifyProductionSigningConfiguration`

fails unless a complete non-QA production signing configuration is present and its keystore file exists. The task never prints passwords or private-key material.

## PR CI signing boundary

PR CI intentionally does **not** inject production signing credentials.

It builds:

- QA APK: signed with the stable test-only QA identity;
- Release APK: unsigned structure candidate;
- Release AAB: unsigned structure candidate.

The CI gate verifies that:

- QA package remains `io.github.ioannes78.voica.qa`;
- Release package remains `io.github.ioannes78.voica`;
- versionCode is 81;
- QA versionName is `1.0.0-rc1-qa`;
- Release versionName is `1.0.0-rc1`;
- Release manifest is not debuggable;
- the PR Release APK has no signer;
- the AAB is structurally valid and contains the base manifest.

An unsigned PR Release APK/AAB is **not** a production release artifact and must not be presented to users as one.

Stage 14.2 release-structure gate result:

- Android PR CI: `#1085`
- run: `37961487016`
- result: `SUCCESS`
- QA signing verification: PASS
- Release APK structure/identity: PASS
- Release AAB structure: PASS
- Release non-debuggable gate: PASS
- PR unsigned-release boundary: PASS
- Room v1-v12 schema gate: PASS

## Production signer fingerprint

Before the first actual V1 signed Release artifact is accepted, Stage 14 must establish and record the production signing-certificate SHA-256 fingerprint.

The intended CI/release input name for that later gate is:

`VOICA_RELEASE_CERT_SHA256`

The signed Release APK must be checked with Android `apksigner` and match the pinned expected certificate digest. That fingerprint is release provenance and is not secret.

The private keystore, private key, passwords, and raw secret values must never be committed to Git or emitted in CI logs.

## Current signer-provisioning gate

The repository does not contain and must not contain a production private key. Stage 14.2 has completed the Release structure/identity side, but a true signed Production APK/AAB remains blocked until the project owner provisions the production keystore/certificate through a secure local/CI secret path.

The production key should be generated or selected in an owner-controlled environment. It should not be created as a committed repository artifact and the public QA key is never an acceptable fallback.

After provisioning, the next Stage 14.2 signing gate is:

1. run `verifyProductionSigningConfiguration` with the production inputs;
2. build signed `assembleRelease` and `bundleRelease`;
3. derive the production certificate SHA-256 fingerprint;
4. pin that fingerprint as release provenance;
5. verify the APK signer and AAB signing identity against the pinned fingerprint;
6. retain only artifact hashes/certificate fingerprint in Stage 14 documentation, never secret material.

## R8 boundary

For Stage 14.2:

- Release `isDebuggable = false`;
- `isMinifyEnabled = false`;
- `isShrinkResources = false`.

This is intentional. Stage 14.3 will turn on and validate R8/resource shrinking against the frozen Stage 14.2 release identity rather than mixing signing/versioning changes with shrinker changes.

## Compatibility and model boundaries

- V1.0 remains the first formally supported install/data baseline.
- Room remains schema 12.
- pre-V1 migration registration remains outside the V1 production runtime contract.
- Stage 13B/13C notification, task-attention, playback and model-install state machines are unchanged by this substage.
- production model channel is not modified by Stage 14.2.
