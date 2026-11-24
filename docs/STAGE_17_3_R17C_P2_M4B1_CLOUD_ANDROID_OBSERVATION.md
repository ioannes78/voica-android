# P2-M4B1 Cloud Android API31 Observation — Gate A

**Status: CODE COMMITTED / CLOUD RUN PENDING / OBSERVATION ONLY / B1 NOT CLOSED.**

## Purpose

Provide a short-lived GitHub-hosted fresh Android API31 AVD as a disposable test environment when the operator has no desktop Android Studio installation. This workflow only produces separately correlated installation observations. It is **not** an independent attestation of prior install history, an enrollment grant, or an authorization to execute fake ownership handoff.

## Source paths

- `.github/workflows/stage17-p2m4b-cloud-observation.yml` — isolated cloud emulator workflow, triggered by changes to its own configuration or read-only policy scripts on the Stage 17 PR. No production merge required.
- `ci/stage17_p2m4b_cloud_observe.sh` — one online emulator serial, verified exact .m4blab QA certificate and app ID, before / after installer observations, host correlation.
- Reuses `ci/stage17_p2m4b_lab_preflight.py`, `ci/stage17_p2m4b_lab_correlate.py`, and exact manifest / safety guards.

## Isolation and retained reports

- API31, `arm64-v8a`, no snapshot loading or saving, forced AVD creation and data wipe **for this newly created ephemeral emulator only**.
- Installs only `io.github.ioannes78.voica.m4blab`. No .qa APK install; no existing user handset, application data, historical P2-M3 poison, physical Android device or cloud secrets involved.
- Report contains `environment.json`, `before.json`, `after.json`, `correlation.json`, `VERDICT.txt`, uploaded for **1 day**. No APK artifact is uploaded.
- Signed Lab APK is verified with a pinned public QA signer and exact app ID before ADB. The source SHA and Android fingerprint are recorded. A fresh disposable cloud emulator is preferable to reusing the previously installed QA handset but is **not by itself a tamper-proof provenance assertion**.
- An install observation pass must say `OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT` and all four authorization fields remain false.

## Boundaries

`IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`, Controller `trustedEnrollment=false`. No owner seed, binder owner admission, child release, model runtime, GPU/JNI, process exit or legacy data clear. Emulator can test *installation-only* mechanics; the initial non-destructive PING/PONG on the operator's API31 device remains a separate narrower observation.

## Remaining work to close P2-M4B1

1. Independent fresh-install provenance operator/authority and installation-specific one-use signed enrollment grant with anti-replay ledger outside restorable app data.
2. Device-side grant verifier, restricted one-time seed and durable readback; independent code review before enabling mock IPC.
3. Separate authorized clean AVDs for B1-2 authenticated fake Parent→Child ownership, B1-3 ordered fake release and interrupted/crash/reopen failure modes, and B1-4 exact-source CI/archived evidence signoff.
4. Preserve PR #37 as OPEN/DRAFT/UNMERGED. Do not claim closure from CI build or this report alone.


## ABI alignment correction — 2026-11-24

- Exact-source cloud run `45351003686` built and verified the Lab APK successfully, but Android installation failed with `INSTALL_FAILED_NO_MATCHING_ABIS` because `app/build.gradle.kts` restricts the APK to `arm64-v8a` while the cloud AVD was `x86_64`.
- The workflow is corrected to use the public GitHub-hosted `macos-15` Apple Silicon runner and an API31 `arm64-v8a` AVD. This matches the APK ABI directly; it does not broaden production ABI filters or alter the APK.
- The previous cloud run is retained as a failed compatibility observation, not a device QA pass. Re-run the corrected exact-source workflow before claiming install-observation success.
- This correction still performs only ephemeral Lab installation and read-only package observations. It does not seed an owner, authorize IPC, or close B1.

## Cloud ARM64 run retrospective — 2026-11-24

- Latest prior exact-source run `45357813225` on `e60f16b13c0a66bca4e76654139d23d3fb798c4f`: **FAIL**, despite Android PR CI `45357813220` and Stage17.3 Quality Diagnostic `45357813428` both **PASS**.
- Under GitHub-hosted `macos-15`, the native `arm64-v8a` AVD process terminated before ADB came online with `HVF error: HV_UNSUPPORTED`, `qemu-system-aarch64-headless: failed to initialize HVF: Invalid argument`, and `QEMU main loop exits abnormally with code 1`. The subsequent ADB `device not found` and boot timeout are consequences, not the root cause. No application installation or evidence correlation took place; artifact upload found no files.
- The preceding `macos-15-intel` / `x86_64` AVD did boot, but the APK's Gradle `abiFilters` only includes `arm64-v8a`; installation failed with `INSTALL_FAILED_NO_MATCHING_ABIS`. An x86 emulated guest cannot be claimed equivalent to physical ARM64 device validation.
- **Do not** mark B1-1 cloud observation PASS, trusted enrollment, owner handoff or B1 closure from these runs. Repeatedly rerunning the same unsupported ARM64 hosted runner without a different hypervisor strategy is unlikely to produce useful QA evidence.
- The workflow now uses per-PR concurrency to cancel stale runs when a newer exact source is queued. That is a CI resource/lifecycle protection, **not** a resolution of host HVF support.
- Next environment decision (requires explicit infrastructure availability): independently managed native ARM64 Android physical device / supported virtualized cloud device farm with controlled fresh install, no restoration, externally audited provisioning, and an authorization ledger; alternatively independently review a truly Java/Kotlin-only Lab artifact for an x86_64 guest, without weakening the production ABI or claiming native runtime equivalence.
- No owner-seed mechanism, signed independent authorization, anti-replay ledger or device-side grant verifier currently exists. The host's own GitHub run JSON cannot serve as the independent enrollment authority. Keep `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false`; keep PR #37 Draft and unmerged.
