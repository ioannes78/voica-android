# Stage 17.3 R17C P2-M4B1 — code-only safe fixture progress

**Status: PARTIAL IMPLEMENTATION / CI PENDING / NO DEVICE TEST / NO FAULT INJECTION / DO NOT DISTRIBUTE APK AS M4B QA HANDOFF.**

Source baseline: P2-M4B0 design 8639188e996fe14eb041eb83e2cb45fde7849098.

## Implemented
- QA and releaseQa only: non-exported :stage17_m4b_parent and :stage17_m4b_child Service declarations.
- The two QA processes are in VoicaApplication's lightweight startup allowlist, not the regular AppContainer.
- Distinct fake identity (model P2_M4B_FAKE_ONLY), generation/nonce/UID/process exact policy, one-shot fake-child admission over Messenger.
- Controller-process-only synchronous SharedPreferences journal in a **new, distinct P2-M4B namespace**. No parent/child SharedPreferences access.
- Missing/invalid/unseeded journal denies ownership. No implicit clean seed. Strict transition codec, no auto-adoption, no post-poison release.
- Seven JVM state/identity/poison tests; static CI guard bans native engine and process termination in this fixture and verifies QA-only manifests.
- Fault-injection flag is hard-disabled, no parent self-exit command is implemented. Main application and old P2-M3 poison evidence remain untouched.

## Not implemented; not claimed
- Controller callback broker with Binder death notification, durable ARMED-before-bind admission transaction, independent observer for retained-child variant, cross-process report/reopen UI.
- Verified never-armed seed mechanism for a separately audited, fresh fixture.
- Surrogate parent self-exit, explicit two-step authorization and on-device A/B lifecycle QA.
- Native owner integration, model-manager mutation barrier, true GPU or JNI recovery.

P2-M4B1 is **not CLOSED** merely because the fixture classes compile.
Wait for exact-source fast CI and diagnostic build results before calling any automated gate PASS.
P2-M4B2 remains separately approval-gated; P2-M4C and P2-N remain blocked.
PR #37 must stay OPEN/DRAFT/UNMERGED; production main unchanged.


## Continuation — Controller Broker (2026-11-18)
- Re-verified production main `28ec9a837c5b`, PR #37 remains OPEN/DRAFT, development baseline `7926272f765a`.
- Previous exact-source PR CI #3025 and diagnostic #213 **FAILED** at `VoicaApplication.kt:69` because literal `\\n` characters had been accidentally written in Kotlin source. Fixed in `a34e0e1b6ca2`.
- Added `Stage17P2M4BController`: main-process-only serialized worker, controller-owned journal claim before bind, Binder `linkToDeath`, no automatic retry, generation/nonce/UID/process validation, fail-closed death/disconnect callback and durable sticky poison transition.
- Static CI now checks controller Binder death hooks and the **hard-disabled IPC admission** switch as well as the disabled fault injection switch.
- Controller's `requestAdmission` currently stops at `IPC_ADMISSION_ENABLED=false`. No new fixture seed, UI activation or device fault test has been authorized; therefore this code is not evidence of a running Binder lifecycle.
- The pure-bound and intentionally-retained-child device scenarios, independent retained child observer, verified fresh-fixture seeding, and simulated parent self-exit remain outstanding. **No MAIN_PROCESS_DEATH_PASS, no B1 closure until exact-source CI succeeds.**

## Continuation — Durable Journal Hardening (2026-11-19)

- Exact-source previous baseline `8de8c724c670`: PR CI SUCCESS and Stage 17.3 Diagnostic APK SUCCESS; PR #37 remains Draft, production main unchanged.
- Journal writes now require synchronous `commit()` **and immediate exact readback** of generation/nonce/phase. A failed commit, exception, or readback mismatch latches `writeUncertain` and denies future claim/transition in the active controller.
- Snapshot decoder requires exactly `explicitly_seeded_v1` (Boolean true) and `owner_record` (valid serialized record); absent, corrupt, wrong-typed, or extra fields deny. This rule has JVM coverage.
- **No automatically trusted seed has been introduced.** A source-proven clean fixture must be separated from the existing QA install with retained P2-M3 evidence before any device admission. An empty SharedPreferences file on the existing QA app is not proof of never-armed status.
- `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false` remain hard-coded; service process admission, fake parent exit, and real native GPU/JNI remain disabled.
- B1 remaining: independently verifiable clean-fixture provisioning with controlled QA admission, read-only report UI, exact-source CI completion, then only separately approved bounded Android Binder test. No B2 fault-injection authorization; no B1 closure claimed.

## Continuation — Read-only QA diagnostic entry (2026-11-20)

- Prior source `9e9a036850d8`: Android PR CI SUCCESS; releaseQa diagnostic APK SUCCESS. Main stayed `28ec9a837c5b`, PR #37 remains Draft.
- Added `Stage17P2M4BReadOnlyActivity` and QA Hub entry, explicitly declared only in `qa` and `releaseQa` manifests, non-exported, no production manifest entry.
- Read-only page displays independent recovered M4B journal verdict and locked admission/fault flags. It has refresh only; no seed/clear/reset, no process bind or kill, no model/JNI/GPU.
- Production code has **no trusted first-install M4B seed** and `IPC_ADMISSION_ENABLED=false`. Existing .qa applicationId is reused by QA and releaseQa, so swapping APKs is not an independent fresh-install attestation.
- This is a source/CI-only UI addition. No on-device cross-process Binder test or on-device read-only observation is claimed.
- For actual IPC admission, a separately isolated test applicationId and explicit verified never-armed enrollment need their own design/review; do not provision this on the installed .qa app that retains historical P2-M3 evidence.
- P2-M4B1 remains partial; P2-M4B2 process termination and P2-M4C real model manager integration are not authorized; PR #37 stays Draft/UNMERGED.

## Continuation — Distinct Lab Application Identity (2026-11-21)

- Re-verified prior `3e7298246a0c` source: Android PR CI and Stage 17.3 Diagnostic APK both **SUCCESS**, main `28ec9a837c5b`, PR #37 OPEN/DRAFT.
- Introduced `m4bLab` Android build type: `applicationIdSuffix = ".m4blab"`, resulting identity `io.github.ioannes78.voica.m4blab` (NOT the installed `io.github.ioannes78.voica.qa`). It is debug-inherited and QA-test signed, not production signed. It uses its **own app sandbox and SharedPreferences** as guaranteed by Android app UID/package separation, rather than reusing the previous QA APK data.
- The `m4bLab` manifest only adds non-exported P2-M4B parent/child services, non-exported read-only page and QA Hub access. No production manifest change; P2-M3 evidence belongs to the old .qa package and must stay untouched.
- `Stage17P2M4BContract.allowPackage` includes the exact new lab identity, and `VoicaApplication` skips heavy startup only for the lab's two exact disposable mock service processes. Production and the P2-L worker allowlist remain distinct.
- Extended JVM tests and CI static requirements to assert exact package/process identity; fast PR CI now **compiles `:app:compileM4bLabKotlin`** in addition to current tests.
- IMPORTANT: A distinct application ID isolates package data, but **does not on its own attest that the package was never previously installed**. There is no clean-seed enrollment, no automatic seeding from absence and no admission toggle. `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`; P2-M4B remains code-only, no on-device test or process death.
- CI success must be observed against the exact new HEAD before claiming this checkpoint PASS. B1 is not yet CLOSED; B2 parent self-exit needs separate explicit authorization. PR remains DRAFT/UNMERGED.

## Continuation — Lab-only launcher / APK binary gate (2026-11-21)

- Prior exact-source checkpoint `18aec681d6a0`: Android PR CI PASS on rerun after transient Robolectric MavenArtifactFetcher network errors; Stage 17.3 diagnostic build PASS.
- `m4bLab` Manifest now removes inherited `MainActivity` via manifest merge directive and makes its sole launcher `Stage17P2M4BReadOnlyActivity`. Historical `.qa` app is a separate package, and all its original launcher behavior remains unchanged.
- `VoicaApplication` bypasses `AppContainer` for exact `io.github.ioannes78.voica.m4blab` main process, as well as the lab's two exact lightweight fake child/parent services. Existing `.qa` and production main processes still initialize normally.
- PR fast CI now assembles `:app:assembleM4bLab` and runs `ci/verify-stage17-p2m4b-lab-apk.sh` to verify binary application ID, version, QA signing identity, single readonly launcher, removal of MainActivity, and exact surrogate parent/child process declarations. **No Lab APK is uploaded or installed**; the separate Stage 17.3 diagnostic artifact remains the legacy `.qa` variant, not the isolated Lab.
- No clean/first-install enrollment code has been added: absence of the Lab journal is not attested NEVER_ARMED. `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false` remain enforced; no Binder lifecycle / self-exit execution or device QA claimed.
- Stage B1 remains **IN PROGRESS** until exact-source CI and enrollment design/code gates are complete. B2 parent-death fault injection requires its own explicit authorization. Production main and PR #37 merge status remain unchanged.
