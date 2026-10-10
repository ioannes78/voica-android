# Stage 14.7A — Production Signing

Status: **ACCEPTED / PRODUCTION SIGNING PASS / FINAL FREEZE BLOCKED**

Stage 14.7A adds and accepts the first production-signing execution path for the accepted Voica V1 release candidate. It does not change product functionality, Room schema, model selection, the production model channel, or the accepted Stage 14.6 RC product behavior.

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

The production signing job consumes the provisioned repository Secrets only through GitHub Actions runtime environment variables:

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

Run #12 confirmed that materialization, certificate validation, build, artifact upload and cleanup all complete successfully.

## 6. Signed artifact build and verification

With the complete production signing configuration present, Stage 14.7A builds:

- production-signed Release APK;
- production-signed Release AAB;
- ReleaseQa APK used for provenance cross-reference.

The signed APK verification requires:

- applicationId: `io.github.ioannes78.voica`
- versionCode: `87`
- versionName: `1.0.0-rc3-r2`
- non-debuggable manifest;
- backup disabled;
- cleartext traffic disabled;
- certificate SHA-256 equals the certificate derived from the production keystore;
- certificate SHA-256 is not the public QA signer.

The signed AAB verification requires:

- expected bundle structure / release identity contract;
- `jarsigner` verifies JAR signature integrity and reports `jar verified.`;
- signer certificate SHA-256 equals the production keystore certificate exactly;
- signer certificate is not the public QA signer.

The Android application signing certificate is intentionally self-signed. `jarsigner -strict` was tested in the first Stage 14.7A run and correctly rejected as an inappropriate PKI trust-chain gate because it converts the expected self-signed-certificate warning into a failure even when AAB signature integrity is valid. The accepted gate therefore verifies signature integrity plus exact signer-certificate identity, rather than requiring a public CA chain.

The existing R8, security/model-boundary, Sherpa JNI/native packaging and Room provenance checks are repeated against the production-signed surface where applicable.

## 7. Accepted CI evidence

Accepted Stage 14.7A workflow head:

- `47e00c7cca656e62ca93373ef652c26e275dbd0d`
- commit: `Stage 14.7A fix AAB signing verification`

CI evidence:

- Android PR CI `#1152`, run `38044710929` — **SUCCESS**
- Android Full Release Gate `#12`, run `38044710898` — **SUCCESS**
- Full Release Gate baseline unsigned job — **SUCCESS**
- Production signing job — **SUCCESS**
- accepted product source lock to `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b` — **PASS**
- production keystore materialization / alias / secret configuration — **PASS**
- production-signed APK/AAB build — **PASS**
- APK production signer / identity verification — **PASS**
- AAB signature integrity / production signer verification — **PASS**
- production provenance generation — **PASS**
- signed artifact upload — **PASS**
- temporary production keystore cleanup — **PASS**

The workflow checkout/build SHA recorded in provenance is `0ada1c4b01257aa0f9d41ad6ef1bd918e8ba48ff`, which is a generated PR merge build identity. The authoritative accepted V1 product source remains `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`.

## 8. Production signer identity

First accepted Voica production signing certificate:

- certificate SHA-256: `f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`
- QA certificate SHA-256: `3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288`
- production certificate differs from QA certificate — **PASS**

This production certificate SHA-256 is now the V1 signing identity that future update/release verification must preserve unless an explicitly planned signing-key migration is performed.

## 9. Production signed provenance

Accepted production provenance for product source `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`:

- versionCode: `87`
- Release versionName: `1.0.0-rc3-r2`
- ReleaseQa versionName: `1.0.0-rc3-r2-export-qa`
- Room schema: `12`
- ABI: `arm64-v8a`
- production model channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production model manifest version: `7`
- production model manifest digest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`
- signed Release APK SHA-256: `dd5be670d39e1d083aac6e2dce1dec651e6fecacfe027a8d0266bb93e0dafd49`
- signed Release APK size: `30,651,367 bytes`
- signed Release AAB SHA-256: `57bf9311b918d9a780c346ea9cca993f10e6328d6ca165b195480e0859f32844`
- signed Release AAB size: `25,602,802 bytes`
- ReleaseQa APK SHA-256: `89137e978e7766abe593f6f96fe402671f056cd10443cb95a0af6aae9cdb358b`
- ReleaseQa APK size: `30,651,379 bytes`
- `production_signed=true`
- production certificate SHA-256: `f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`

The signed APK and AAB were also downloaded from the Actions artifacts and their file-level SHA-256 values were independently recomputed; both exactly match the generated production provenance.

## 10. Accepted Actions artifacts

Production artifacts from Full Release Gate #12:

- `Voica-V1-RC-production-signed-apk`
  - artifact id: `11666543541`
  - artifact ZIP digest: `sha256:a4da209c76bb7a4ecd182af42261f69c91e00dcfe505a250d7fef1359db3702a`
- `Voica-V1-RC-production-signed-aab`
  - artifact id: `11666882923`
  - artifact ZIP digest: `sha256:25434c3f694814694267fbedadd024de261c06726e055de1a1664a20b24d72f7`
- `Voica-V1-RC-production-signing-provenance`
  - artifact id: `11667077422`
  - artifact ZIP digest: `sha256:d988510ee0e66f0213c8753b207eadd88ff8bd7152bc8d246864cb1bddcd49f8`

The production signing provenance artifact contains the provenance text and Release mapping only. The production keystore and password values are not artifacts.

## 11. Stage 14.7A acceptance

Stage 14.7A acceptance gates:

1. normal Full Release Gate PASS — **PASS**;
2. accepted RC product-source lock PASS — **PASS**;
3. production signing configuration PASS — **PASS**;
4. production APK signature / identity PASS — **PASS**;
5. production AAB signature / identity PASS — **PASS**;
6. production certificate SHA-256 recorded — **PASS**;
7. signed APK and AAB SHA-256 / sizes recorded — **PASS**;
8. signed artifacts uploaded successfully — **PASS**;
9. temporary keystore cleanup PASS and keystore absent from uploaded artifact set — **PASS**.

Therefore **Stage 14.7A is ACCEPTED** and the previous `production signer UNPROVISIONED` blocker is closed.

## 12. Final Freeze remains blocked

Stage 14.7A removes the production-signer blocker only.

Stage 14 Final Freeze/Handoff remains blocked until the separate Stage 14.7B real-device `30 / 60 / 120` minute stability evidence is completed and recorded. PR #24 must remain Draft/Open and must not be merged merely because production signing succeeds.
