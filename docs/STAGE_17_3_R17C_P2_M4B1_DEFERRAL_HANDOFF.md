# Stage 17.3 R17C P2-M4B1 — Deferred QA handoff / 主线恢复

Decision date: **2026-11-24**
Decision authority: **explicit user instruction — defer P2-M4B1 and resume Stage 17 mainline**
Status: **DEFERRED / NOT CLOSED / B1-4 NO-GO**
Repository: `ioannes78/voica-android`
Development branch: `stage17-fully-offline-intelligence`; PR #37 remains **OPEN / DRAFT / UNMERGED**
Verified pre-decision HEAD: `7736bc323f8128a0fb18d58d032f7ef68174a29b`
Production `main`: `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12` — do not modify.

## 1. Reason and decision

No independently audited fresh-fixture history, externally held signing/ledger authority, one-use device-bound grant, nor independently reviewed physical-device lifecycle evidence is currently available. The user has no dedicated Android QA handset and explicitly elects to defer this *optional isolated mock process-ownership QA workstream*, rather than invent operator provenance or bypass admission gates.

This is **a schedule/scope deferral, not PASS, CLOSE, WAIVER of security requirements, removal of prior Poison, or authorization to enable unavailable runtime paths**.

## 2. Exact carry-forward facts

| Item | Disposition |
| --- | --- |
| B1-1 independent never-armed provenance, one-use enrollment, external atomic consumption and device readback | **BLOCKED / DEFERRED** |
| B1-2 real Android fake-only Parent→Child one-owner handoff | **NOT VERIFIED / DEFERRED** |
| B1-3 real durable fake release, explicit reopen, interruption and deny-on-poison | **NOT VERIFIED / DEFERRED** |
| B1-4 signed evidence and final close/freeze | **NO-GO / NOT CLOSED** |
| Isolated `.m4blab` fake protocol, Journal/codec, non-authorizing grant/ledger probes and tests | **RETAIN IN DRAFT BRANCH**; no new execution authority |
| Cloud API31 native-free install observation `45365485994` | **PASS (installation-only sub-check)**; not enrollment/owner QA |
| Exact-source Android PR CI `45406393680` | **SUCCESS**, baseline `7736bc323f81` |
| Exact-source Stage 17.3 Quality Diagnostic APK `45406393671` | **SUCCESS**, baseline `7736bc323f81` |
| Cloud AVD run `45406393984` | **IN PROGRESS at decision verification**; obtain final evidence separately, never assume PASS |
| Production/default ARM64 ABI and existing local LLM behavior | **UNCHANGED** |

Historical P2-M3 `.qa` Poison is immutable evidence: no clear/reset/overwrite, no silent app data wipe. `Stage17P2M4BContract.IPC_ADMISSION_ENABLED=false`, `FAULT_INJECTION_ENABLED=false`, and Controller `trustedEnrollment=false` remain mandatory. No native/JNI/GPU/process-kill/self-exit testing or real-owner admission. Public CI signing key and locally signed synthetic grant **must never** be treated as independent QA authority.

## 3. Stage 17 mainline restart scope

Resume **Stage 17.4 — Offline Q&A + Audio Citation** requirements and architecture preparation, with minimal local retrieval design (lexical/FTS) needed for evidence candidates. Continue to use the Stage 17.1 single `AppLocalLlmRuntime`, Stage 17.2/17.3 `StructuredTranscriptInput`, canonical transcript/audio evidence IDs, local-only semantics and fail-closed summary/GPU boundaries.

17.4 Q&A must **not** depend on `Stage17P2M4BController`, mock Binder Parent/Child ownership, the disposable Lab journal, seed/grant inspection, or enabling disabled switches. Stage 17.5 full local RAG remains a separate reserved scope; Stage 17.4 may **plan** a minimal deterministic lexical candidate interface, without prematurely adding database migrations, embeddings or a second runtime owner.

The original Stage 17 roadmap requires Stage 17.3 real-device QA **and Freeze/Handoff before Stage 17.4 implementation**. This deferral **does not close Stage 17.3**. To keep moving without falsifying that gate:
- **Immediately allowed:** 17.4 requirements review, architecture/dependency audit, QA matrix and implementation plan drafts; no production feature code or Room migration under this handoff.
- **Not yet authorized by this decision:** 17.4 production code implementation, Stage 17.3 CLOSED, PR merge, production GPU change, and claims of physical-device PASS.
- **Next gate:** independently account for remaining Stage 17.3 core-product real-device QA, then Stage 17.3 Freeze/Handoff; alternatively request a separately documented explicit revision of that stage dependency from the user before any 17.4 implementation.

## 4. Reactivation conditions for deferred P2-M4B1

Reopen as a distinct, separately approved QA task only when an independently controlled disposable test fixture (emulator or a qualified dedicated device), auditable never-armed history, trusted external issuer key management, atomic non-rollbackable consumption ledger, on-device verifier/seed review and scoped test approval are all available. A generic user phone or an empty journal alone is not sufficient.

On reopening, re-verify `main`, draft PR/head, exact CI, immutable old `.qa` Poison, API31 install evidence and all four B1 acceptance gates; do not reuse a stale grant/fixture. No reactivation is implicit in Stage 17.4 work.

## 5. Mainline next action

Prepare and review `Stage 17.4 — Offline Q&A + Audio Citation` requirements V1, including Q&A evidence identity, no-evidence refusal, local retrieval interface, privacy and audio seek, without committing feature implementation ahead of the Stage 17.3 closure/dependency decision.
