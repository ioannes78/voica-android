# Voica CI Strategy

Status: ACTIVE — Post-V1

## Goals

CI is split by intent so everyday development does not pay the cost of production R8/signing on every commit.

1. Fast feedback for every code PR.
2. A production-like QA APK only when real-device testing is requested.
3. Production signing only for an explicit candidate or release.
4. Build the production Release APK/AAB once per candidate/release gate.
5. Keep production signing secrets out of routine PR jobs.

## Channels

| Trigger | Gate | App identity / signer | Purpose |
| --- | --- | --- | --- |
| normal PR | Fast PR Gate | no distributable production artifact | unit tests + debug compile |
| PR title contains `[APK]` | Fast PR Gate + QA APK Gate | `io.github.ioannes78.voica.qa`, QA signer | frequent real-device QA; coexists with production app |
| PR title contains `[CANDIDATE]` | Fast PR Gate + Production Gate | `io.github.ioannes78.voica`, production signer | overwrite-upgrade and data-retention testing |
| PR title contains `[RELEASE]` | Fast PR Gate + Production Gate | `io.github.ioannes78.voica`, production signer | final release artifact |
| manual Android PR CI | `fast` or `qa` input | as selected | ad-hoc validation |
| manual Android Production Gate | `candidate` or `release` input | production signer | controlled production build |

`releaseQa` is deliberately a separate Android application and cannot overwrite the production app. A production candidate uses the production application ID and production certificate so it can validate the real upgrade/data-retention path. Its `versionCode` must be greater than the production build already installed on the test device. If the final release must overwrite an installed candidate, the final release must advance `versionCode` again.

## Build ownership

### Fast PR Gate

Runs the unit/debug contract only. It is the primary same-repository Gradle cache writer. Fork PRs remain read-only.

### QA APK Gate

Runs in parallel with the Fast PR Gate when `[APK]` is requested. It builds only `:app:assembleReleaseQa`, verifies R8/security/native packaging, and uploads one QA APK. Its Gradle cache is read-only to prevent cache reservation contention.

### Production Gate

Does not run for normal or `[APK]` PRs. For `[CANDIDATE]`, `[RELEASE]`, or manual production dispatch it:

1. runs cheap repository/version/model/Room preflight checks before toolchain setup;
2. verifies production-signing guardrails before materializing secrets;
3. materializes the keystore only under `RUNNER_TEMP`;
4. builds only `assembleRelease + bundleRelease` with production signing;
5. verifies APK/AAB identity, R8, security, Sherpa/native packaging, Room and signer;
6. writes provenance and uploads only signed APK, signed AAB and provenance/mapping;
7. deletes the temporary keystore in an `always()` cleanup step.

The Production Gate does **not** rebuild `releaseQa` and does not build an unsigned Release first.

## Cache and artifact policy

- Fast PR Gate: cache writer for same-repository PRs.
- QA APK Gate: cache read-only.
- Production Gate: cache read-only.
- APK/AAB artifact uploads use `compression-level: 0`; APK/AAB are already compressed containers.
- QA APK retention: 3 days.
- Production candidate/release retention: 14 days.
- Routine CI does not upload redundant unsigned APK/AAB artifacts.

## Configuration cache

Fast PR and QA gates use Gradle configuration cache. Production signing initially remains on `--no-configuration-cache` until the release path has a separate compatibility qualification; release reliability has priority over a small additional speed gain.

## Real CI benchmark — 2026-10-10

The optimization benchmark used the same Draft PR and the same `[APK]` intent before and after the strategy change.

### Old strategy

- Android PR CI #1166: approximately **8m05s** total; main Gradle invocation **6m52s**.
- Android Full Release Gate #26: approximately **11m21s** total; main Gradle invocation **9m50s**.
- `[APK]` wall-clock completion: approximately **11m21s**.
- Approximate combined runner occupancy: **19m26s**.
- Both heavy jobs attempted to write Gradle cache entries and produced cache reservation conflicts.
- The Release Gate redundantly built `releaseQa + unsigned Release APK + Release AAB` even though the request was only for a QA APK.

### Optimized strategy

- Android PR CI #1171 — Fast PR Gate: **3m17s** total; Gradle unit/debug gate approximately **2m03s**; configuration cache stored successfully.
- Android PR CI #1171 — QA APK Gate: **4m58s** total; `assembleReleaseQa` Gradle build approximately **3m47s**; configuration cache stored successfully.
- Android Production Gate #31: **SKIPPED by design** for `[APK]`.
- `[APK]` wall-clock completion: approximately **5m01s**.
- Approximate combined runner occupancy: **8m15s**.
- QA and production jobs use read-only Gradle caches, so the old multi-writer cache reservation conflict is removed.

Measured improvement:

| Metric | Old | Optimized | Reduction |
| --- | ---: | ---: | ---: |
| `[APK]` wall-clock | 11m21s | 5m01s | **55.8%** |
| combined runner occupancy | 19m26s | 8m15s | **57.5%** |
| first fast feedback | 8m05s | 3m17s | **59.4%** |

These are observed GitHub Actions timings, not estimates. Individual future runs can vary with GitHub-hosted runner/network/cache conditions.

## Production Candidate validation

The production path was separately exercised on the same CI-only PR with `[CANDIDATE]` after the `[APK]` benchmark.

- Android Production Gate #32: **SUCCESS**.
- Production preflight: **SUCCESS**.
- Production signed candidate/release job: approximately **5m05s**.
- The only production Gradle build was `assembleRelease + bundleRelease`: **3m23s**, 320 actionable tasks (200 executed, 120 from cache).
- QA APK Gate: **SKIPPED**, as intended for `[CANDIDATE]`.
- Production certificate validation, signed APK/AAB verification, R8, security, Sherpa/native packaging, Room schema, provenance, artifact upload, and temporary-keystore cleanup all passed.
- Provenance correctly records `releaseqa_apk_sha256=N/A` and `releaseqa_apk_size=N/A`, proving the production path no longer rebuilds QA solely for provenance.
- Production Gradle cache was read-only and did not write cache state after the job.

The candidate validation used the unchanged V1 metadata (`versionCode 88`, `versionName 1.0.0`) only to validate the CI machinery. It is **not** a real overwrite-upgrade candidate and must not be used to test upgrading an installed V1.0 build with the same versionCode. A real Post-V1 candidate must use a versionCode greater than the installed production build; if the final release must then overwrite that candidate, the final release must advance versionCode again.
