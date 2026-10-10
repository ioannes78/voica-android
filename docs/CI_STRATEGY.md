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

## Benchmark baseline

The optimization benchmark uses the same Draft PR and `[APK]` title before and after the strategy change.

Old-strategy baseline on 2026-10-10:

- Android PR CI #1166: approximately 8m05s total; main Gradle invocation 6m52s.
- Android Full Release Gate #26: approximately 11m21s total; main Gradle invocation 9m50s.
- `[APK]` wall-clock completion was therefore approximately 11m21s.
- Approximate combined runner occupancy was 19m26s.
- Both heavy jobs attempted to write Gradle cache entries and produced cache reservation conflicts.

The optimized-run measurements are added after the same PR is rerun with this strategy.
