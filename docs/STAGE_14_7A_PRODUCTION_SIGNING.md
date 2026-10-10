# Stage 14.7A — Production Signing

Status: **IMPLEMENTED / PRODUCTION SIGNING CI PENDING / FINAL FREEZE BLOCKED**

Stage 14.7A adds the first production-signing execution path for the accepted Voica V1 release candidate. It does not change product functionality, Room schema, model selection, the production model channel, or the accepted Stage 14.6 RC product behavior.

## 1. Re-verified baseline

Re-verified before this slice on 2026-10-10:

- repository: `ioannes78/voica-android`
- `main`: `36c25e4f0de8c515b4d950df8230e601590917ce`
- Stage 14 branch: `stage14-v1-release-freeze`
- branch head before Stage 14.7A: `6ce1ba96281b30e2607900bbf3482542cc1107a0`
- PR #24: OPEN / Draft
- accepted Stage 14.6 product source: `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`
- versionCode: `87`
- Release versionName: `1.0.0-rc3-r2`
- ReleaseQa versionName: `1.0.0-rc3-r2-export-qa`
- Room schema: `12`
- production model channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- model manifest version: `7`
- model manifest digest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

The Stage 14.6 acceptance record remains authoritative for real-device RC smoke acceptance.

## 2. GitHub Secret contract

The production signing job consumes the already provisioned repository Secrets only through GitHub Actions runtime environment variables:

- `VOICA_KEYSTORE_BASE64`
- `VOICA_STORE_PASSWORD`
- `VOICA_KEY_ALIAS`
- `VOICA_KEY_PASSWORD`

They map at runtime to the Gradle production signing contract:

- decoded temporary keystore path -> `VOICA_RELEASE_STORE_FILE`
- `VOICA_STORE_PASSWORD` -> `VOICA_RELEASE_STORE_PASSWORD`
- `VOICA_KEY_ALIAS` -> `VOICA_RELEASE_KEY_ALIAS`
- `VOICA_KEY_PASSWORD` -> `VOICA_RELEASE_KEY_PASSWORD`

No keystore bytes, passwords, key password, or Secret value is committed to the repository or written to release provenance.

## 3. Execution boundary

Production signing is intentionally narrower than the normal Full Release Gate.

The production-signing job runs only when all of the following are true:

1. the event is the existing pull request;
2. the PR head repository is the same repository, not a fork;
3. the PR head branch is exactly `stage14-v1-release-freeze`;
4. the normal unsigned Full Release Gate has already passed for the run.

The job is not enabled for arbitrary branches or fork pull requests.

## 4. Accepted RC product-source lock

Before production credentials are materialized, the job compares build-affecting product source against the accepted Stage 14.6 source:

`c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`

The following build/product paths must remain identical:

- `app/`
- `core/`
- `engine/`
- `gradle/`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- root `build.gradle.kts`
- `settings.gradle.kts`

Documentation, release-verification scripts and the release workflow may evolve to collect signing evidence, but the accepted V1 product source may not silently drift during Stage 14.7A.

Any product-source drift fails the signing job before the production keystore is decoded.

## 5. Keystore handling

The Base64 keystore Secret is decoded only to:

`${RUNNER_TEMP}/voica-production.jks`

The workflow:

- uses a restrictive `umask` before writing the file;
- validates that the keystore exists and that the configured alias can be read;
- extracts the production certificate SHA-256 fingerprint;
- rejects the public QA certificate fingerprint;
- passes the temporary path to Gradle only at runtime;
- never uploads the keystore as an Actions artifact;
- removes the temporary keystore in an `always()` cleanup step.

## 6. Signed artifact build and verification

With the complete production signing configuration present, Stage 14.7A builds:

- production-signed Release APK;
- production-signed Release AAB;
- ReleaseQa APK used for provenance cross-reference.

The signed APK must verify:

- applicationId: `io.github.ioannes78.voica`
- versionCode: `87`
- versionName: `1.0.0-rc3-r2`
- non-debuggable manifest;
- backup disabled;
- cleartext traffic disabled;
- certificate SHA-256 equals the certificate derived from the production keystore;
- certificate SHA-256 is not the public QA signer.

The signed AAB must verify:

- expected bundle structure / release identity contract;
- valid strict JAR signature;
- signer certificate SHA-256 equals the production keystore certificate;
- signer certificate is not the public QA signer.

The existing R8, security/model-boundary, Sherpa JNI/native packaging and Room provenance checks are repeated against the production-signed surface where applicable.

## 7. Artifacts

On success the workflow uploads only:

- `Voica-V1-RC-production-signed-apk`
- `Voica-V1-RC-production-signed-aab`
- `Voica-V1-RC-production-signing-provenance`

The provenance artifact contains the release provenance text and Release mapping file. It does not contain the production keystore or passwords.

## 8. Stage 14.7A acceptance rule

Stage 14.7A is accepted only after one same-repository Stage 14 run proves all of the following:

1. normal Full Release Gate PASS;
2. accepted RC product-source lock PASS;
3. production signing configuration PASS;
4. production APK signature / identity PASS;
5. production AAB signature / identity PASS;
6. production certificate SHA-256 recorded;
7. signed APK and AAB SHA-256 / sizes recorded in provenance;
8. signed artifacts uploaded successfully;
9. keystore absent from uploaded artifacts.

Until that run succeeds, this file remains `PRODUCTION SIGNING CI PENDING`.

## 9. Final Freeze remains blocked

Passing Stage 14.7A removes the production-signer blocker only.

Stage 14 Final Freeze/Handoff remains blocked until the separate Stage 14.7B real-device `30 / 60 / 120` minute stability evidence is completed and recorded. PR #24 must remain Draft/Open and must not be merged merely because production signing succeeds.
