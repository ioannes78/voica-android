# Stage 17.3 P2-M4B1 — Independent enrollment authority handoff (B1-1)

**Decision: NOT IMPLEMENTED / NO GRANT ISSUED / NO DEVICE ENROLLMENT. This document is a security and acceptance contract, not an authorization.**

Scope: ephemeral, disposable `io.github.ioannes78.voica.m4blab` only. Never apply to `.qa`, production `main`, historical P2-M3 Poison, model runtime, native GPU/JNI, or a pre-existing handset. PR #37 remains Draft/Unmerged. `IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`, and Controller `trustedEnrollment=false`.

## 1. Trust boundaries

| Actor | Allowed responsibility | Cannot establish by itself |
| --- | --- | --- |
| Independent fixture operator | Witness a newly created emulator/profile/test device, prevent restore, preserve external creation/install timeline, attest fixture provenance | Ownership from an empty app Journal or `pm list packages` alone |
| Independent grant issuer | Sign a short-lived, one-fixture/one-install challenge grant with an offline or dedicated private key | Global grant uniqueness, durable consumption, Android runtime safety |
| Independent ledger | Atomically consume a grant and its installation challenge once and maintain externally durable audit/history | Device history without operator-provided provenance |
| Android Lab inspector | Verify pinned external trust roots, exact observed APK/signing identity, nonce, time, issuer and ledger signatures | Independent historical attestation from app-local storage |
| Controller/Journal | Remain denied until an independently reviewed enrollment transaction and one-time durable seed are demonstrated | Granting trust from the pure inspection helpers |
| Public GitHub CI | Test parser rejection, static source separation, exact build identity, disposable install observation | Signing real grants, hosting private keys, approving enrollment |

**Mandatory trust anchor:** operator/issuer and ledger public keys must be provisioned through a separately audited channel; do not accept arbitrary public keys shipped inside untrusted JSON or the QA-signing certificate as authority. Key rotation, compromise response and expiration must be defined before B1-1 can pass.

## 2. Fresh-fixture evidence required before any grant

An independent operator must preserve a time-ordered, externally held record of: fixture creation provenance and Android build/fingerprint; device and Android user/profile identity; no-restore policy and backup disabled in the merged APK; app package absence *observed* before install; exact source commit, APK SHA-256, package ID and signer SHA-256; controlled one-time install; the installation-specific challenge and independent issuer authorization.

A single cloud before/after package observation, an empty SharedPreferences Journal, app `firstInstallTime`, or a self-produced JSON file cannot prove never-armed history. The successful cloud run `45365485994` is installation-only observation, not operator attestation or enrollment.

## 3. External consumption state machine (required semantics)

```text
UNISSUED
  -> GRANT_ISSUED (approved fixture + challenge + exact binary identity)
  -> CONSUMED_DURABLE (one atomic transaction: grantId + installChallenge)
  -> ACKNOWLEDGED (only after durable commit and independent readback)

Duplicate/expired/contradictory input -> DENY
Ambiguous transaction outcome -> STOP / QUARANTINE fixture until external reconciliation
Any previously armed, released or poisoned fixture -> DENY RE-ENROLLMENT
```

External ledger must enforce global uniqueness of **both** `grantId` and `installChallenge`, not just adjacent sequence numbers. It must commit consumption atomically across competing requests, retain tombstones indefinitely for the applicable QA evidence lifetime, and reject copied grants after app uninstall/reinstall, restore, or host restart. An unchanged signed receipt is **not** a license to execute a second enrollment.

Requirements:
- Validate independently authenticated fixture ID, installed APK SHA/signing certificate, source SHA, challenge, package and grant expiry **within** the atomic consume operation; all mismatches deny.
- Unique constraints on fixture ID, installation challenge and grant ID; serialize concurrent requests and never return two successful consumption decisions.
- Never delete or reset consumption/poison tombstones to unblock another attempt; start with an independently audited *new* fixture.
- Ack a unique monotonic sequence and externally signed receipt **only after** ledger commit and exact durable readback. On timeout or missing ACK, do not retry as a new enrollment; query external ledger under independent authority, and fail closed if still ambiguous.
- Preserve atomic audit log and backup/restore policy outside the device's restorable storage. No private keys, tokens, or real grants in public CI or repository.

## 4. Envelope/canonicalization contract for review

Current **untrusted** source prototypes:
- `Stage17P2M4BGrantEvidenceInspection`: ECDSA P-256-style SHA256withECDSA signature checks (currently caller-supplied public keys; not pinned) for grant ID, installation challenge, Lab package, APK SHA, certificate SHA, source SHA, 10-minute-or-less validity, and a consumption receipt.
- `Stage17P2M4BReplayLedgerInspection`: caller-signed checkpoint with observed sequence adjacency and timeline, output **only** `ORDER_CONSISTENT_NOT_REPLAY_PROOF`.

Before any trusted integration: formally version and freeze canonical byte encoding and curve requirements; independently pin issuer and ledger key identities; bind fixture identity and a non-rollbackable externally witnessed install-session ID into the approved envelope; define an interoperable test vector suite; require monotonic authority time; enforce app/device readback after a one-shot seed. The present Kotlin probes alone **do not** define or implement this complete protocol.

Do not interpret `SIGNATURES_AND_FIELDS_COHERENT_NOT_AN_ENROLLMENT` or `ORDER_CONSISTENT_NOT_REPLAY_PROOF` as authorization, and never map either result to `trustedEnrollment=true`.

## 5. Acceptance / negative test matrix

| Case | Required external decision | Evidence |
| --- | --- | --- |
| Independently witnessed fresh fixture, authorized exact install, first valid grant | One `CONSUMED_DURABLE` at most, readback and signed receipt | Operator attestation + ledger transaction/audit |
| Concurrent duplicate submissions on one grant/challenge | Exactly one consumption, all others DENY | Concurrent traces + uniqueness proof |
| Replayed receipt / stale signed checkpoint / rollback of operator database view | DENY, no second owner | Non-rollbackable authoritative ledger readback |
| App data cleared, backup restored, APK replaced, signer mismatch, source changed | DENY | Independent install and package provenance |
| Wrong device/user/fixture, old challenge, grant expired or clock skew | DENY | Fixture identity and independently trusted time |
| Interrupted consumption before commit | Zero successful grants, retry only after independent reconciliation | Atomic transaction/audit records |
| Commit succeeds but ACK is lost | One durable consumption, no second grant; quarantine pending reconciliation | Ledger commit and operator review |
| Owner Journal already armed, released, uncertain or poisoned | DENY and never re-seed same fixture | Durable Journal + external operator audit |
| Missing issuer root, untrusted key, missing ledger, unreachable authority | DENY; `IPC_ADMISSION_ENABLED` remains false | Device/CI negative log |
| Historical `.qa` or production package attempts B1 flow | DENY without touching prior data | Package/process boundary evidence |

## 6. Actual B1-1 handoff gate

The operator must provide all of: (a) independently signed fixture history; (b) independently provisioned trusted key fingerprint/rotation policy; (c) audited external ledger with atomic uniqueness, persistence and fault testing; (d) exact sample grant + consumption receipt, redacted safely for review; (e) separately reviewed on-device verifier and one-time Journal seed implementation; (f) host/device negative traces and exact-source CI; (g) explicit written authorization for a disposable one-owner **fake-only** scenario.

**Absent any one item, B1-1 is BLOCKED.** Do not approve B1-2 Parent→Child ownership, B1-3 release/reopen or B1-4 close. Existing cloud install observation and JVM simulations may remain PASS only in their narrow scope. No native teardown, process killing, fault injection, or production merge is authorized by this handoff.
