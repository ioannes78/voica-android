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

## Continuation — Child ACK hardening (2026-11-21)

- Prior exact-source baseline `e7dbc721cf66` passed both PR CI and Stage17.3 Quality Diagnostic APK; PR #37 remains Draft / unmerged; main unchanged.
- Source audit found that `Stage17P2M4BParentService.childReceiver` accepted fake child READY using Bundle generation/nonce/process/UID without complete fake-model identity, Binder-origin UID or one-shot callback enforcement. The child response likewise omitted `EXTRA_MODEL`.
- Fixed fake child READY to include `P2_M4B_FAKE_ONLY` identity. Parent now requires exact `Message.sendingUid`, matching generation, nonce, fake model, child process, app UID, armed state, child-admission-sent and not-yet-accepted. Duplicate or malformed replies report `WHAT_DENIED`, which Controller treats as unresolved and poisons if an attempt were ever armed. Child bind ComponentName mismatch also reports DENIED.
- Extracted pure `Stage17P2M4BChildAckPolicy` with JVM tests for accepted fake READY, stale/replayed tokens, duplicate delivery, unarmed/wrong UID/process/model; CI source check asserts policy is actually used.
- No new seed or enrollment entrypoint. In particular, the independent Lab package ID alone does not prove never-armed. `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false`; no real Binder lifecycle, fake process kill, JNI, GPU/AUTO or on-device QA. P2-M4B1 is **not closed** until fresh-install enrollment and required real Binder evidence are separately reviewed and tested.

## Continuation — Host-observed Lab installation preflight (2026-11-21)

- Starting point `2647daf39de9`: PR CI SUCCESS and Stage17.3 Diagnostic APK SUCCESS; PR #37 OPEN/DRAFT/UNMERGED, main `28ec9a837c5b`.
- New read-only host tool `ci/stage17_p2m4b_lab_preflight.py` takes exact `--lab-apk`, `--serial`, and `--stage before|after`. It verifies exact Lab APK applicationId with `apkanalyzer`, computes the APK SHA-256, reads an explicitly selected ADB device state, and reads `pm path` for Lab and existing `.qa` independently. It prints JSON to stdout, performs **no installation, uninstall, clear/reset, or Journal mutation**, and refuses a wrong APK/serial or wrong before/after package presence.
- `PREINSTALL_ABSENCE_OBSERVED` and `POSTINSTALL_PRESENCE_OBSERVED` are observations, **NOT independent historical attestation**. Reinstall/data restore/other users cannot be excluded merely from package state. Both reports explicitly state `never_armed_proven=false`, `owner_seed_authorized=false`, `ipc_admission_authorized=false`, `fault_injection_authorized=false`.
- Added host-level unit tests with mocked ADB (no connected Android device required), and wired them to PR CI; static CI also rejects any privilege/grant/reset behavior in the host script.
- Illustrative operator-only commands from a trusted host (NO automatic execution):
  `python3 ci/stage17_p2m4b_lab_preflight.py --stage before --serial <EXACT_ADB_SERIAL> --lab-apk app/build/outputs/apk/m4bLab/app-m4bLab.apk`
  and after a **separately approved manual** isolated installation:
  `python3 ci/stage17_p2m4b_lab_preflight.py --stage after --serial <EXACT_ADB_SERIAL> --lab-apk app/build/outputs/apk/m4bLab/app-m4bLab.apk`.
- Separate operator evidence is still required for a no-history/no-restore clean Lab installation. No code creates a `VERIFIED_NEVER_ARMED` record, and no app-only observation may be used to seed a historical owner Journal. A separate reviewed enrollment design would need to bind external proof to the isolated installation and ensure no reset/re-enroll after poison. Not authorized or implemented here.
- `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`; no device activity, no Binder admission, no parent self-exit, no GPU/JNI. M4B1 is **IN PROGRESS**. PR #37 remains Draft/UNMERGED.

## Continuation — Correlate host installation observations (2026-11-21)

- Reviewed PR #37 at `deb57951c4f2`: OPEN/DRAFT/UNMERGED, production main `28ec9a837c5b`; Android PR CI PASS. Stage17.3 Diagnostic APK run `44901583216` was still IN_PROGRESS at initial recheck, so not claimed as PASS.
- Added `ci/stage17_p2m4b_lab_correlate.py` to compare separately captured `--before` and `--after` JSON observations from `ci/stage17_p2m4b_lab_preflight.py`. It validates exact Lab APK identity, matching SHA-256, same explicit device serial, before absent/after present, UTC timestamps ordered, typed historical-QA observations, and all authorization fields explicitly **false**. Missing, contradictory, reordered, or privilege-claiming evidence is denied.
- Successful output is exactly `OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT`: it does **not** prove a first-ever install, non-restoration, clean durable owner state, or authorize any seed, Binder command or fault injection. Correlator is pure JSON validation, no subprocess, no device contact, no journal or installer mutation.
- Added mocked/pure host regression tests for valid observational correlation and invalid serial, package, digest, timestamp, stage, malformed field, forbidden or missing authorization flags, plus CI static requirements.
- Operator-only example (inputs must come from separately preserved trusted evidence; NOT run in CI):
  `python3 ci/stage17_p2m4b_lab_correlate.py --before before.json --after after.json`
- **No device was accessed**, no Lab APK installed or uploaded, no first-install enrollment/Journal seed implemented. A separate independently audited operator-controlled enrollment/attestation procedure remains needed. `IPC_ADMISSION_ENABLED=false`; `FAULT_INJECTION_ENABLED=false`; P2-M4B1 remains IN PROGRESS; PR #37 remains Draft/unmerged; M4B2 parent process exit requires explicit separate authorization.

## Continuation — Pin signed Lab APK identity before any host ADB observation (2026-11-22)

- Reverified `7860a188dc06`: PR #37 OPEN/DRAFT/UNMERGED, main `28ec9a837c5b`, Android PR CI and Stage17.3 Quality Diagnostic APK both SUCCESS.
- Audit finding: the host preflight previously checked only `apkanalyzer manifest application-id`, not the APK's signing identity; an unrelated APK with the same application ID could be used to produce misleading observations.
- The read-only preflight now runs `apksigner verify --print-certs`, checks exactly one signer #1 SHA-256 certificate digest and requires the pinned public QA signer SHA-256 `3df9c619ad0d06f2fabb24232420bf056ca2d8ea9413e7f776669a591974f288` **before any ADB device access**. Output records `apk_signer_sha256`.
- The JSON correlator now requires that same pinned signer in both before and after observations; unit tests cover invalid, missing and duplicated signer results as well as wrong signer evidence. Static CI enforces this boundary.
- This is a host-side *evidence quality* improvement, **not authentication of who captured the JSON**, proof of historical install absence, authorization to seed a Journal, or real cross-process Binder test. For that reason `never_armed_proven=false`, `owner_seed_authorized=false`, `ipc_admission_authorized=false`, `fault_injection_authorized=false` remain unchanged.
- No APK installed or distributed, no Journal written, no existing `.qa` data touched, no process exit or real LLM/GPU/JNI action. M4B1 remains IN PROGRESS; PR #37 stays Draft/unmerged.

## Continuation — Lab-only non-destructive IPC admission authority boundary (2026-11-23)

- Reverified baseline `96ba47660886`: main `28ec9a837c5b` unchanged; PR #37 OPEN/DRAFT/UNMERGED, Android PR CI SUCCESS and Stage17.3 Quality Diagnostic APK SUCCESS.
- Audit: `Stage17P2M4BController.requestAdmission()` previously tested `allowPackage`, which intentionally permits both historical `.qa` read-only diagnostics and the disposable `.m4blab` package. Those broad read-only permissions must not become owner-admission permissions if a later developer enables IPC.
- Added pure `Stage17P2M4BAdmissionPolicy` requiring **exact Lab appId and process**, enabled fake IPC gate, independent trusted enrollment, and an owner Journal in a permitted phase. In Controller, `trustedEnrollment = false` is wired as a **literal fail-closed gate**, evaluated before `journal.claim` or `bindService`. Merely toggling `IPC_ADMISSION_ENABLED` later cannot activate this path.
- Regression tests include counterfactual enabled-IPC/true-external-proof cases, and explicit DENY for historical QA, production/wrong process, missing enrollment proof, null/interrupted/poisoned owner, and speculative locally encoded NEVER_ARMED. Static CI enforces that Controller invokes the policy with literal false and the policy distinguishes Lab from read-only QA.
- This does **not** implement, mint, import, infer, or persist a trusted enrollment grant. The standalone host JSON observation/correlation and public QA signing key cannot prove never-armed history. An independent, reviewed device-provisioning and no-restore first-run attestation remains an external prerequisite; until then no new owner writes or Binder admission are permitted.
- `IPC_ADMISSION_ENABLED=false`; `FAULT_INJECTION_ENABLED=false`; no Lab APK installed, published, or exercised on a device; no process death, GPU/JNI, or real AI. P2-M3 sticky poison evidence untouched, PR #37 DRAFT/UNMERGED, main unchanged. P2-M4B1 remains IN PROGRESS.

## Continuation — Lab-only unarmed Android Binder liveness probe (2026-11-23)

- Baseline `e3f660e4dc28`: Android PR CI SUCCESS; separate Stage 17.3 diagnostic workflow `45181003796` remained IN_PROGRESS when rechecked. PR #37 OPEN/DRAFT/UNMERGED; main `28ec9a837c5b` unchanged.
- Added isolated `Stage17P2M4BBinderProbeActivity` registered **only** in `src/m4bLab/AndroidManifest.xml` as **non-exported**, launched from an explicit Lab-only navigation button on the otherwise read-only Journal page. Historical `.qa` cannot access the probe via its QA hub; production manifest has no probe.
- User-triggered once per Activity instance: Binder binds disposable parent Service and sends `WHAT_PROBE_PING` with one random UUID token and `P2_M4B_FAKE_ONLY`. Parent validates exact Lab package/process, Messenger `sendingUid`, controller process, token, fake-only model, and `!armed`, then sends PONG with parent process and UID. Page validates exact response `sendingUid`, UID, process, nonce and model; it reports PASS/DENY and unbinds. Timeout/error/disconnect also deny. No automatic retry. Any reported PASS establishes **only that the two observed processes exchanged a valid Binder message at that moment**, not independent process survival, cleanup or owner state.
- Probe **never opens/writes the owner Journal**, calls `requestAdmission`, sends `WHAT_ARM`, binds the fake child, runs native LLM/JNI/GPU, or issues any process exit. No `VERIFIED_NEVER_ARMED` seed, no installer/data reset, no prior P2-M3 journal touch. An unbound Service may terminate naturally under Android lifecycle rules; no forced death.
- Pure JVM policy tests cover valid fake PING/PONG, unexpected package/process, Binder UID/model/nonce mismatch, malformed token and already armed parent. CI static checks enforce non-exported Lab-only registration and prohibit owner mutation in the new Activity.
- This provides a **code-ready non-destructive Binder liveness probe**, not an executed device test. No Lab APK is uploaded/installed or test executed on real Android; no true enrollment mechanism has been authorized. `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false` stay fixed. M4B1 remains IN PROGRESS until device evidence and independent enrollment requirements are satisfied.

## Continuation — Verifiable cross-process PONG PID / local report (2026-11-23)

- Reverified source `0f01d92af21c`: Android PR CI SUCCESS on attempt 2 (initial Robolectric MavenArtifactFetcher network failures); Stage17.3 Quality Diagnostic APK SUCCESS; PR #37 DRAFT/OPEN/UNMERGED; main `28ec9a837c5b`.
- The earlier PING/PONG identity policy checked the reported parent process name but not the actual reported PID. Strengthened `Stage17P2M4BProbePolicy.acceptsPong` to require positive, distinct controller and parent PIDs in addition to exact UID/process/model/token. A matching package and self-reported process string alone cannot pass this stronger check. JVM tests explicitly deny same-PID, invalid-PID replies.
- The explicit Lab probe page now displays a local copyable `P2-M4B1_LAB_BINDER_PROBE_V1` report: verdict, package, controller PID, validated parent PID (or NOT_VERIFIED), OS API, start/end epoch milliseconds, probe UUID token and fixed `owner_admission=false`, `journal_mutation=false`, `fault_injection=false` labels. Report explicitly limits proof to ONE_OBSERVED_BINDER_EXCHANGE_ONLY and never establishes enrollment/ownership release/process survival. No log upload or persistent data write. Clipboard copy requires a user tap.
- Existing one-shot request detaches on result/timeout/disconnect. Handler timeout callback is removed during completion to prevent a stale timeout overwriting a completed result. CI static verification asserts this and the independent PID check.
- Strengthened `ci/verify-stage17-p2m4b-lab-apk.sh` to require the Lab probe component in the *merged APK manifest*, `exported=false`, no intent-filter or launcher. It remains unavailable in old `.qa` or production manifests.
- Still **no Lab APK installed or published**, no on-device Binder execution, no self-exit, no fake-child admission, no native LLM/JNI/GPU. Host preflight/observation pair is not an enrollment grant. Historical P2-M3 poison not touched; `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`; PR #37 remains DRAFT/UNMERGED, main unchanged. P2-M4B1 is IN PROGRESS pending controlled device validation and separately approved trusted enrollment.

## Continuation — Explicit QA-only Lab APK handoff (2026-11-23)

- Reverified baseline `55d83d0c3496`: Android PR CI SUCCESS; Stage17.3 Quality Diagnostic APK still IN_PROGRESS at this review; PR #37 OPEN/DRAFT, production main `28ec9a837c5b` unchanged.
- Previous PR fast-gate had a signed/validated `app-m4bLab.apk` but intentionally **never uploaded** that build, so on-device PING/PONG could not be verified through a proper downloadable handoff.
- Added a **separate** `m4b-lab-handoff` QA job in `.github/workflows/android-ci.yml` triggered **only** on an explicit PR title **edit** whose new title starts `[M4B-LAB-APK]`. No code push or ordinary PR synchronize builds/uploads this artifact. Existing `[APK]` QA workflow remains separate.
- The job checks out exact `github.event.pull_request.head.sha`, executes static QA boundary and read-only host unit tests, builds `:app:assembleM4bLab` only, verifies signed APK's merged Manifest (one Lab launcher, probe non-exported, isolated parent/child processes, no inherited MainActivity, backup disabled), app ID and public QA test certificate, emits `SHA256SUMS.txt`, then uploads **only** the independent Lab APK and SHA file for **1 day**. The normal fast gate still **never uploads** Lab APKs; source editing alone does not trigger upload.
- `io.github.ioannes78.voica.m4blab` is a separate Android package, so this test APK cannot be installed as an update to `.qa`. No installation, data wipe, Journal seed, owner admission, fault injection, or real model operation is run by the CI job.
- **On-device operator handoff** after explicit QA artifact dispatch: download ZIP from that exact GitHub Actions run, verify `app-m4bLab.apk` SHA-256 and package ID, install alongside existing .qa (never uninstall .qa / clear app data), open the Lab launcher -> `Open isolated Binder liveness probe` -> `Run one read-only Binder PING`; copy the resulting report. A PASS requires distinct valid Lab/parent PIDs and validated Binder UID/token/model/process, but does not prove native owner release or never-armed history.
- M4B1 still **IN PROGRESS** until the actual Android device report is inspected. No automatic enrollment or seed. PR remains Draft/unmerged, P2-M3 historical poison untouched; `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`.

## Device evidence — API 31 Lab Binder liveness PASS (2026-11-23)

- User-submitted device report `P2-M4B1_LAB_BINDER_PROBE_V1`: `verdict=PASS: isolated parent Binder PONG identity verified`.
- Lab package `io.github.ioannes78.voica.m4blab`, Android API 31; `controller_pid=14477`, `parent_pid=17787` (positive, distinct). Timestamp window: `start_epoch_ms=1795441922710` to `finish_epoch_ms=1795441923748`, elapsed **1,038 ms including Service bind and callback**; this is not a Binder-only latency benchmark.
- The report supplied a per-request UUID; the raw value is not reproduced in the repository to avoid unnecessary disclosure. The Lab probe source verifies Binder sender UID, reported app UID/process/model, nonce and distinct positive PID. This is **operator-submitted evidence**, not independently collected ADB process-lifetime observation.
- Report contains `owner_admission=false`, `journal_mutation=false`, `fault_injection=false`, and scopes itself to `ONE_OBSERVED_BINDER_EXCHANGE_ONLY`; these are **probe report assertions**, not external proofs of historic non-use or durable state.
- At intake, exact Lab handoff run `45187132155` SUCCESS and Stage17.3 diagnostic run `45187132238` SUCCESS on source `a606615a027d2421e5f3f4f522977ffdf93940e2`. PR #37 OPEN/DRAFT/UNMERGED; main `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12` unchanged.
- **Gate decision: `LAB_BINDER_PING_PONG_REAL_DEVICE_PASS` (API 31, operator report).** P2-M4B1 remains **IN PROGRESS**, because independent trusted enrollment, durable owner Journal claim/transition, fake child-owner admission and release/lifecycle/restart tests remain unverified. A PONG must never authorize `VERIFIED_NEVER_ARMED`, owner admission, simulated process exit, journal reset or PR merge.
- `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false` remain hard-locked; existing P2-M3 poison must not be touched.

## Continuation — defense-in-depth child and controller Journal admission (2026-11-23)

- Starting verified source `6dce2f3321d3`: operator API31 Binder PING/PONG PASS recorded; PR #37 OPEN/DRAFT, main unchanged. This is a liveness observation, not verified never-armed history.
- Found a source-level gap: mock child `WHAT_CHILD_ADMIT` inbound branch checked message identity and one-shot flag but did not independently require `IPC_ADMISSION_ENABLED`; Parent/Child `onBind` and ControllerJournal controller check used read-only `allowPackage`, including historic `.qa` package.
- Closed those gaps with `Stage17P2M4BChildAdmissionPolicy` and service wiring: exact `.m4blab` app/process, enabled fake IPC, unadmitted, valid generation/UUID/fake model, Binder sender UID, controller-supplied UID, exact parent process and reply channel are all mandatory. Parent `WHAT_ARM`, Parent/Child binding, and controller Journal access now independently check `isLabPackage`. Controller retains literal `trustedEnrollment=false` and `IPC_ADMISSION_ENABLED=false`; neither can be enabled by the PING/PONG report.
- Added negative JVM tests for old QA/production/wrong process, repeated fake admission, disabled IPC, bad generation/nonce/UID/model/parent identity and absent reply. Static CI enforces policy wiring at real messenger reception and package boundary.
- Added `Stage17P2M4BLifecycleSimulationTest`: simulated exact serialized journal snapshots and crash/reopen with every intermediate phase blocked, normal release requires ordered fake terminal + child ACK + parent ACK + durable commit, missing ACK or failed commit denies, corrupt snapshots and stale attempts deny. All fake memory-level only: these are **not** Android SharedPreferences durability, real Binder owner adoption/release, or independent proof of first-use.
- **Four-gate accounting**: (1) independently attested first-install enrollment **BLOCKED / NOT IMPLEMENTED**; (2) real fake-owner Parent→Child IPC **BLOCKED** by never-authorized enrollment; (3) pure JVM state-machine simulation expanded but real restart/durability/normal-release remains **UNVERIFIED**; (4) B1 closure **NOT MET**. Liveness subgate alone passed.
- No APK install/uninstall/reset, process exit, native/GPU/JNI or historical Poison access. PR remains DRAFT/UNMERGED; main unchanged. No false `VERIFIED_NEVER_ARMED` seed.

## Continuation — Full fake-only terminal/child/parent ordered release code (2026-11-23)

- Added independent `Stage17P2M4BChildAdmissionPolicy` at the actual fake Child Messenger ingress. The service now checks `IPC_ADMISSION_ENABLED` and exact Lab package/process/UID/nonce/model independently, rather than trusting Parent to gate all commands. Parent fake owner ARM and Parent/Child `onBind` also enforce exact `.m4blab` identity. Historical `.qa` retains read-only Journal recovery via `allowPackage`, but `claim`/`transition` reject non-Lab writes.
- Added explicit **fake-only** terminal and release sequence: on durable CHILD_ADMITTED, Controller commits FAKE_OWNER_ACTIVE then requests terminal; Parent validates controller Binder identity and reports FAKE_TERMINAL_OBSERVED; Controller durably commits TERMINAL_FAKE_OBSERVED before requesting child release; Child checks original generation/nonce/UID/process/fake model and responds once; Parent validates the release ACK and reports it; Controller durably commits CHILD_RELEASE_ACK_FAKE_OBSERVED before requesting Parent unbind; Parent unbinds and reports its fake ACK; Controller durably commits PARENT_RELEASE_ACK_FAKE_OBSERVED and then RELEASE_CONFIRMED_DURABLE. Every missing/duplicate/out-of-order/mismatched callback and commit failure must deny or poison, never infer release from Binder death or a timeout.
- New pure JVM tests cover fake child release guard and Parent release predicates/one-shot ACK identity, in addition to fake durable-snapshot lifecycle simulation and historical QA isolation. The static CI contract checks the wired policies and exact sequencing. No native/LLM/GPU release is inferred by these fake messages.
- **Important: this is code-only, disabled and not proven on-device.** The Controller retains literal `trustedEnrollment=false`, contract `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false`, and no initial Journal seed. There is no device enrollment verifier or authorized seed. A fake protocol code path that is unreachable by design is not a passing ownership QA.
- Four gate accounting remains: 1) independent trusted enrollment and one-time seed **NOT IMPLEMENTED, needs separately authenticated provenance**; 2) fake Parent→Child owner handshake **IMPLEMENTED IN SOURCE, BLOCKED FROM DEVICE EXECUTION**; 3) fake normal-release/restart/poison **PURE JVM TESTS ADDED; REAL DEVICE NOT VERIFIED**; 4) closure **NO-GO** until real source-pinned CI and independently reviewed device evidence. Historic P2-M3 Poison remains untouched, main unchanged, PR #37 Draft.

## Continuation — one-shot owner authorization cannot be recycled (2026-11-23)

- Verified baseline `b55461ac0b48`: PR #37 OPEN/DRAFT, main `28ec9a837c5b` unchanged, exact-source Android PR CI and Stage17.3 diagnostic both SUCCESS.
- Source audit found `Stage17P2M4BCodec.claim` and `blocked` permitted a second owner claim after `RELEASE_CONFIRMED_DURABLE`. For **P2-M4B1 disposable one-attempt testing**, historical fake release must not become an implicit new grant.
- Restricted the B1 owner claim to generation-zero `VERIFIED_NEVER_ARMED` with empty nonce only; release remains readable historical success but is **blocked** for another attempt. This ensures even a hypothetical future device-provisioning grant cannot be silently reused by a second fake model session.
- Updated JVM lifecycle / codec / admission counterfactual regression tests and CI source guard; normal first-time attempt and ordered fake terminal / child ACK / parent ACK state transitions remain unchanged.
- This work does **not** supply independent never-armed provenance, seed the Journal, turn on IPC, perform real owner Binder exchange, erase or reset app data, or authorize process death. `IPC_ADMISSION_ENABLED=false`; `FAULT_INJECTION_ENABLED=false`. Remaining 4 closure conditions in `STAGE_17_3_R17C_P2_M4B1_TRUSTED_ENROLLMENT_OPERATOR_GATE.md` remain not fully satisfied.

## Continuation — Robolectric full regression CI unblock (2026-11-23)

- One-shot fake owner claim hardening committed at `0cc5884d8102`. New full CI attempt 1 had 14 `MavenArtifactFetcher.java:130` Android runtime-jar network download failures and one separate pre-existing transient realtime audio test failure. Attempt 2 no longer reproduced the realtime test failure, but still had exactly 14 `MavenArtifactFetcher` runtime download failures. These were **not M4B1 policy assertion failures**; PR CI remained FAILED.
- Pinned the Robolectric JVM Test task **runtime artifact repository URL** to canonical Maven Central host `https://repo.maven.apache.org/maven2` (instead of Robolectric default `repo1.maven.org`) in `app/build.gradle.kts`. This preserves the **full same test suite** and Gradle dependency verification; no test skip, mocking or application release behavior changed.
- Treat this as a CI infrastructure remediation hypothesis until exact-source Android PR CI SUCCESS confirms it; do not mark P2-M4B1 closed. Underlying independently signed provisioning and real owner/Child/restart device evidence remain absent.

## Continuation — test-only Conscrypt TLS workaround (2026-11-23)

- Exact-source CI `45201741072` confirmed the alternate official Maven Central host alone did not fix 14 Robolectric `MavenArtifactFetcher` download failures; the prior transient realtime test exception did not recur.
- A documented Robolectric 4.17 / Conscrypt 2.6.0 XDH TLS handshake regression can cause these lazy Android runtime-jar failures. Added **JVM-test-only** strict `org.conscrypt:conscrypt-openjdk-uber:2.5.2` version constraint, leaving Android production runtime and the full test suite untouched.
- The Conscrypt compatibility hypothesis is **not PASS until exact-source CI completes**. No M4B1 owner enrollment, seed, fault, JNI or GPU changes.

## Continuation — confirmed Maven Central HTTP 429 and upstream Google mirror (2026-11-23)

- CI `45203825006` diagnostic XML established exact root cause: `java.io.IOException: Server returned HTTP response code: 429` for `org.robolectric:android-all-instrumented:15-robolectric-13954326-i7` from `https://repo.maven.apache.org/maven2`. This is a host artifact rate-limit, not a B1 assertion failure.
- Robolectric's own upstream `settings.gradle.kts` explicitly prefers `https://maven-central.storage-download.googleapis.com/maven2/` for `android-all-instrumented` because Maven Central returns HTTP 429 from heavy CI traffic. Configured **JVM Test task only** to download Robolectric runtime jars from Google's Central mirror, preserving SDK versions and all tests.
- Removed the speculative Conscrypt `2.5.2` version constraint because the actual failure is HTTP 429, not XDH/TLS; no production/test dependency version needs downgrading. Maintained bounded failure diagnostics and no-test-skip policy.
- Exact-source CI against this mirror change remains required before claiming green status. Unfulfilled M4B1 independent enrollment and real owner/Child lifecycle device gates are unchanged.
