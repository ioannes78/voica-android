# Stage 14.5 — Release CI

Status: **PASS / accepted release-infrastructure slice.**

This document records Stage 14.5 Release CI acceptance. It is not the Stage 14 Final Freeze/Handoff and it does not represent a signed production release.

## Scope

Stage 14.5 hardens and proves the V1 release pipeline without changing the accepted v87 product behavior.

Frozen product baseline remains:

- versionCode: `87`
- Release versionName: `1.0.0-rc3-r2`
- production-like QA versionName: `1.0.0-rc3-r2-export-qa`
- Room schema: `12`
- ABI: `arm64-v8a`
- production model channel contents: unchanged

No UI, ASR, diarization, AI Summary, BLE, playback, export-product behavior, Room schema, or production model-channel content was changed by this stage.

## Dedicated Full Release Gate

Stage 14.5 adds:

- `.github/workflows/android-release-gate.yml`

The dedicated gate builds all three release surfaces on `[APK]` / `[RELEASE]` PRs and manual runs:

1. production-like `releaseQa` APK;
2. unsigned production Release APK;
3. unsigned production Release AAB.

It verifies:

- release identity and version contract;
- QA signing certificate identity;
- unsigned production Release boundary while production credentials are absent;
- non-debuggable / backup-disabled / cleartext-disabled manifest boundary;
- immutable V1 production model-channel pin;
- R8 mapping/configuration outputs;
- Sherpa post-R8 Java/JNI ABI;
- arm64-only native packaging;
- retained `libsherpa-onnx-jni.so` and `libonnxruntime.so`;
- continued absence of `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so`;
- Release AAB structure;
- Room schema lineage 1..12 and current schema v12;
- production signing negative guardrails;
- future production certificate SHA-256 contract;
- release provenance generation.

## Shared CI verification scripts

Stage 14.5 adds reusable release scripts under `ci/`:

- `prepare-opus-source.sh`
- `verify-release-artifact.sh`
- `verify-release-aab.sh`
- `verify-release-security.sh`
- `verify-sherpa-package.sh`
- `verify-r8-outputs.sh`
- `verify-room-schemas.sh`
- `verify-production-signing-config.sh`
- `verify-production-signing-boundary.sh`
- `generate-release-provenance.sh`

The dedicated workflow calls these scripts instead of duplicating the prior long inline verification blocks.

## Opus source availability / integrity

Opus remains exactly:

- version: `1.6.1`
- SHA-256: `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`

The Release Gate prefetches the source with bounded retries, rejects any checksum mismatch, and may fall back from the primary Xiph HTTPS endpoint to the OSUOSL Xiph mirror serving the same archive.

`engine/opus/src/main/cpp/CMakeLists.txt` also accepts the already verified `VOICA_OPUS_ARCHIVE` and retains pinned URL-hash verification for direct/local builds. This improves availability without weakening source integrity.

## Final CI evidence

Authoritative source head:

- `6d2b0af18d327fa47ff0f9f737812b24087cf4fe`

GitHub PR merge-ref build SHA used by the Full Release Gate:

- `fbb55d690c7da4e48912eb7d51254c1b41c7cbde`

Normal PR CI:

- workflow: `Android PR CI`
- run number: `1143`
- result: **SUCCESS**

Dedicated release CI:

- workflow: `Android Full Release Gate`
- run number: `3`
- run id: `38038369112`
- result: **SUCCESS**

Every Full Release Gate verification and artifact-upload step completed successfully.

## Final unsigned release provenance

Release APK:

- status: **UNSIGNED — not a production-distribution artifact**
- size: `30,639,079` bytes
- SHA-256: `f1ec50384ca990f753d73b8a6817dd2b2c2bc456213d7242512dd5144931c775`

Release AAB:

- status: **UNSIGNED — not a production-distribution artifact**
- size: `25,589,049` bytes
- SHA-256: `15473304c10c508fd66f10e2e4939810c5b8920c1637af8306364edd0443121b`

Production-like ReleaseQa APK:

- size: `30,651,379` bytes
- SHA-256: `d72df7c931fe6515da1d40df81054177d24078f0197741fa303567f11949a4b8`

Model provenance:

- production model-channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifest version: `7`
- production manifest digest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Signing provenance:

- `production_signed=false`
- `production_cert_sha256=UNPROVISIONED`

The production private key remains external to the repository and has not been provisioned. The public QA signer remains rejected for production use.

## Artifact naming boundary

The Full Release Gate uploads deliberately explicit artifact names:

- `Voica-release-gate-releaseqa-apk`
- `Voica-release-UNSIGNED-apk`
- `Voica-release-UNSIGNED-aab`
- `Voica-release-provenance`

The unsigned Release artifacts must never be presented as a final V1 production release.

## Acceptance

Stage 14.5 changes release infrastructure/build provenance only. The already accepted Stage 14.4.1 v87 real-device PASS remains valid; no repeat full ASR/VAD/diarization/export QA is required for this slice.

Stage 14.5 is accepted because:

- normal PR CI remains green;
- the independent Full Release Gate is green;
- all three release surfaces were actually produced;
- unsigned signing boundaries are enforced;
- artifact hashes and source/build SHA provenance are recorded;
- R8/Sherpa/native/security/Room/model-channel gates remain green;
- no product behavior was changed.

Next stage: **Stage 14.6 — V1 Release Candidate**.
