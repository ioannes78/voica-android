# Stage 17.3 R17C P2-M4B1 — B1-4 final gate review

Status: **NO-GO / NOT CLOSED**. This is a gate review, NOT a completed B1 freeze or a device test.
Repository: `ioannes78/voica-android`.
Development branch: `stage17-fully-offline-intelligence`, PR #37 remains DRAFT / UNMERGED.
Production main baseline observed: `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`.

## Verified evidence

1. Previous exact-source development HEAD `72fdf763b48c9224fc42856c8b3e6b18e8632470` passed Android PR CI `45204447153` and Stage17.3 Quality Diagnostic APK `45204447199`. Android Production Gate was skipped, not PASS.
2. Subsequent progress-document-only commit `87c99b57100c3137107f0423e15971a23c3b261f` has separate Android PR CI SUCCESS `45217355839`. The diagnostic workflow was still in progress when reviewed, so no success is claimed here.
3. Operator-supplied API31 `P2-M4B1_LAB_BINDER_PROBE_V1` reported one non-destructive Lab Controller↔Parent PING/PONG PASS with two distinct PIDs. This is not proof of the fresh-install history, persistent owner release, child admission, or Android child survival.
4. Source review: `Stage17P2M4BContract.IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`; `Stage17P2M4BController.requestAdmission` supplies `trustedEnrollment=false`; `Stage17P2M4BControllerJournal` provides no seed API; source tests for fake release do not validate actual Android Binder release.

## Explicit B1-1 .. B1-4 decision matrix

| Gate | Required | Observed | Decision |
| --- | --- | --- | --- |
| B1-1 trustworthy enrollment | Audited never-armed disposable fixture, no restore, independent installation-specific one-time signed authorization and anti-replay consumption, device verifier and synchronous exact readback | No independent fixture provenance, operator authorization, grant verifier or seed | **BLOCKED** |
| B1-2 fake-only Parent→Child ownership | Real Android exact source, durable intent before bind, authenticated Parent/Child admission, one owner | Mock protocol + JVM assertions exist; device admission disabled | **NOT VERIFIED ON DEVICE** |
| B1-3 release and interruption | Real Android ordered fake terminal / child ACK / parent ACK, journal durable release, reopen and interruption/fail-closed evidence | Source + JVM simulation only; prior PING/PONG never created owner | **NOT VERIFIED ON DEVICE** |
| B1-4 close/freeze | Exact-source full CI, approved scoped device traces, provenance, no legacy poison regression, independent signoff | CI for earlier HEAD passed; required device and provenance artifacts absent | **NO-GO** |

## Required independent evidence and order (operator-owned)

- Establish a **new disposable Android user/emulator/test device installation** and independently record its lifecycle from creation; disable app restoration and preserve external audit history. Do not use absence of SharedPreferences, `pm path`, `firstInstallTime`, or old PING/PONG as sole proof.
- Independently approve an installation-bound one-use enrollment grant with a private signing/approval credential **outside APK, source and public CI**; record grant consumption outside restorable app state. Implement and separately review on-device verifier before any seed is permitted.
- After review and explicit authorization for a controlled non-destructive fake-owner scenario, use a fresh fixture to prove: one-shot durable seed, ARMED before bind, validated mock child admission, ordered terminal/ACK release, durable reopen, and denial of second ownership. Use distinct clean fixtures for interruption/poison cases; never reset a poisoned fixture.
- Preserve exact APK digest, certificate digest, source SHA, device/user identity, process PID/UID, nonce/generation (redact sensitive tokens in public reports), timestamped chronological messages, committed journal verdict, observed service/process lifecycle, screenshot/device report, and independently signed verification.
- Recheck new exact-source CI and produce B1-4 closure **only when all four gates pass**. Never reinterpret Android process/binder death as a native GPU/JNI teardown ACK.

## Hard safety boundary

Do not modify the historical `.qa` P2-M3 sticky poison or clear its app data.
Keep `IPC_ADMISSION_ENABLED=false` and `FAULT_INJECTION_ENABLED=false` until the independently reviewed authorization mechanism and separately approved device QA are available.
No fake surrogate-parent self-exit in B1; no native model, GPU/AUTO or JNI tests; no PR #37 merge.
If evidence remains missing, maintain **B1 IN PROGRESS / B1-4 NO-GO**, not CLOSED.

## 2026-11-24 evidence update — cloud installation sub-check only

At exact source `3739ac3cb98d5cf99b7f1325fe177c0c0af969ca`, Android PR CI `45365486378`, Stage 17.3 Quality Diagnostic APK `45365486011`, and API31 x86_64 native-free Lab cloud observation `45365485994` all completed **SUCCESS**. The cloud report archive SHA-256 is `283e7c17a6904cde5472cd3365d7d5576867f7d68b1588004305642239aa14a4` (GitHub artifact ID `14906904251`, retention one day). Host-observed `.m4blab` package absence before installation and presence after installation correlate to the same APK SHA-256 `22d7fe10d7f0c6e9deecdef7edf13fb50fbc49b671486388860cd4d95eeb9c50`, pinned QA signer and emulator serial. All recorded authorization fields remain **false**.

**Decision:** `Cloud API31 installation observation = PASS`, while `B1-1 trusted enrollment = BLOCKED`, `B1-2 real Binder ownership = NOT VERIFIED`, `B1-3 durable release/reopen/interruption = NOT VERIFIED`, and `B1-4 = NO-GO / NOT CLOSED`. The cloud observation verdict is expressly `OBSERVATIONS_CORRELATED_NOT_AN_ENROLLMENT`. This test used an intentionally native-free disposable x86_64 Lab variant; it proves neither ARM64 production behavior nor audited never-armed history. Do not seed an owner or enable IPC admission based on this result.

The independent operator-signed one-use enrollment, external anti-replay ledger, on-device grant verifier, scoped real-device lifecycle test and independent signoff are still missing. Details and exact evidence references: `docs/STAGE_17_3_R17C_P2_M4B1_CLOUD_ANDROID_OBSERVATION.md`.
