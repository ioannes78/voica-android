# Stage 17.3 R17C P2-M4B1 — Trusted enrollment gate and final closure evidence

**Status: GATE NOT SATISFIED. Not an authorization to seed, admit a fake owner, or inject a fault.**
Repository: ioannes78/voica-android, development PR #37 (Draft/unmerged).
The P2-M3 sticky mock poison in the old `.qa` package is immutable historical evidence.

## 1. Facts that are and are not verified

- An operator-supplied Android API31 report proves one scoped `.m4blab` Controller → disposable Parent Binder PING/PONG exchange. It reports distinct PIDs 14477/17787, 1,038 ms end-to-end. **The sample proves no owner release, child admission or prior clean installation.**
- An existing `.m4blab` install and absence of a Journal, by themselves, cannot prove first-ever use, absence of Android backup/restore, or that no other APK with the public QA signing key was ever installed.
- The CI public QA signing certificate is *not an enrollment authority*. Host read-only preflight and pair correlation intentionally always output `never_armed_proven=false`.
- The Android production/legacy QA package, old P2-M3 Journal, current independent Lab package, and any new disposable provisioning fixture must not share owner state.

## 2. Separate, independently reviewed provisioning evidence (not yet available)

Before implementing any one-time seed, an independent QA operator must supply evidence from a **new controlled disposable Android user/emulator/test installation** that has never held an M4B owner. The operator must document:

1. Dedicated device/emulator or new Android user/profile identity and immutable exact setup/build provenance, not just the app reporting an empty SharedPreferences file.
2. A freshly provisioned test package/UID with no app restore; backup disabled in merged APK; observed package absence before a controlled first install; an audit trail establishing the test fixture history independently of app-reported state.
3. The exact APK SHA-256, application ID, pinned QA signing certificate SHA-256, source HEAD, installed package identity and process identities.
4. A one-time grant signed/approved by an independent operator, bound to an installation-specific challenge and a single fresh fixture. The private authorization credential **must not be in source, the shipped APK, or public CI artifact**.
5. A durable non-resettable (relative to the test operator's independent ledger) grant-consumption record. App uninstallation, restored data, altered APK, expired grant, repeated grant or any prior owner activity must reject; never reissue after Poison without a separate reviewed fixture.
6. A reviewed device-side grant-verification implementation and synchronous exact readback of the verified never-armed seed. All ambiguous cases reject.

**These requirements are not currently implemented.** Do not reinterpret the existing operator PING/PONG report, `pm path`, `firstInstallTime`, installer name, public QA signature, or self-generated JSON as a cryptographic provenance grant. No current handset operation is requested.

## 3. Four explicit B1 closure gates

| Gate | Required result | Status |
| --- | --- | --- |
| 1: External never-armed provenance + one-time enrollment | independently reviewed proof, anti-replay, durable one-time seed and device readback | BLOCKED — no independent grant or verifier |
| 2: Mock Parent → Child ownership handoff | armed intent durable before bind, exact UID/process/generation/nonce/model, authenticated ACKs, never two owners | SOURCE CODE ONLY; IPC admission disabled |
| 3: Normal fake release / restart / uncertain commit | actual Android ordered terminal/child ACK/parent ACK, durable release; test interruption and deny on reopen | JVM simulation available; DEVICE NOT VERIFIED |
| 4: Final B1 signoff | exact-source CI plus verified device evidence and no regression/Poison changes; closure report | NOT MET |

## 4. Stop criteria

- Keep `Stage17P2M4BContract.IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`, and Controller `trustedEnrollment=false` until the independent grant system is implemented, reviewed and proven.
- No main-process termination, no disposable-parent forced exit, no child restart, and no native/JNI/GPU/model manager integration in B1.
- If any source mutation enables admission before grant verification or an APK upgrade touches the legacy `.qa` Poison, STOP and do not claim a QA PASS.
- Source/JVM CI PASS and PING/PONG PASS may be retained as narrower milestones but are **not substitutes** for the four closure gates.

The next actionable external dependency is a separately audited fresh test fixture with operator-signed never-armed provenance. Source-only implementation cannot manufacture this fact.
