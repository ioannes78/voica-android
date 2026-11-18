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
