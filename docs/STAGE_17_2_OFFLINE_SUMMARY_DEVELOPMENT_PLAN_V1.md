# Stage 17.2 — Offline Summary V1

## Development Plan V1

Status: **CONFIRMED / ACTIVE IMPLEMENTATION PLAN**

Requirements: `docs/STAGE_17_2_OFFLINE_SUMMARY_REQUIREMENTS_V1.md`

Stage 17 P1 status: **CLOSED / FROZEN**

Repository: `ioannes78/voica-android`

Production branch: `main`

Development branch: `stage17-fully-offline-intelligence`

Pull request: `#37 — Stage 17 — Fully Offline Intelligence`

PR policy: **DRAFT / OPEN / DO NOT MERGE WITHOUT SEPARATE EXPLICIT USER AUTHORIZATION**

Implementation baseline before P0: `462dd8868996992653c0f231fd0be5e2d6c8db0a`

---

## 1. Development objective

Implement one production-facing fully offline transcript-summary path while reusing the Stage 17 P1 local-LLM foundation and the existing summary persistence/version/search/export/evidence product stack.

Target path:

```text
Current Effective Transcription
  -> StructuredTranscriptInput
  -> OfflineSummaryPrompt
  -> Direct Input Budget Gate
  -> OfflineSummaryCoordinator
  -> AppLocalLlmRuntime
  -> AUTO backend
  -> Gemma 4 E2B
  -> SummaryResultCodec
  -> optional bounded local repair
  -> validated AiSummaryResult
  -> Evidence mapping
  -> existing AiSummary persistence/version lifecycle
  -> existing Summary UI/search/export/evidence playback
```

Stage 17.2 remains direct-summary only. Long-transcript hierarchy is deferred to Stage 17.3.

---

## 2. Frozen implementation principles

1. Formal LLM entry point: `AppLocalLlmRuntime.generate(...)` only.
2. Product backend request: `AUTO` only.
3. No direct `Engine` or `LiteRtLocalLlmRuntime` ownership in summary code.
4. No second runtime coordinator.
5. No cloud fallback.
6. No provider selector.
7. Room stays version 16.
8. Existing summary/version/evidence/search/export infrastructure is reused.
9. Existing canonical playback remains the only evidence playback owner.
10. Existing production model channel is not promoted automatically.
11. PR #37 remains Draft/Open throughout implementation and QA unless separately authorized.

---

## 3. P0 — Documents and frozen contracts

### Scope

Create and freeze:

- `docs/STAGE_17_2_OFFLINE_SUMMARY_REQUIREMENTS_V1.md`
- `docs/STAGE_17_2_OFFLINE_SUMMARY_DEVELOPMENT_PLAN_V1.md`

Record:

- confirmed requirements;
- confirmed implementation plan;
- P1 runtime/model/R8 hard contracts;
- Room v16 freeze;
- no-cloud-fallback rule;
- direct-summary-only boundary;
- Stage 17.3 deferral;
- PR #37 no-merge rule.

### Gate

Only `docs/` changes are allowed in P0.

Run/verify Fast PR Gate before P1 product-code work.

---

## 4. P1 — Offline summary core contracts

Primary module: `:core:ai` or the current equivalent AI core package.

Expected responsibilities:

- offline-summary input/result contracts;
- prompt renderer;
- prompt version constant `170201`;
- direct-input budget contract;
- deterministic display-text formatter independent of Compose;
- product-level failure taxonomy where appropriate.

Suggested implementation units:

- `OfflineSummaryContracts.kt`
- `OfflineSummaryPrompt.kt`
- `OfflineSummaryBudget.kt`
- `OfflineSummaryDisplayFormatter.kt`

File grouping may follow the existing module style, but the responsibilities must remain separable and testable.

### Prompt requirements

Prompt rendering must include:

- Simplified Chinese output default;
- current structured summary schema;
- epistemic-status rules;
- strict evidence allowlist semantics;
- JSON-only response;
- no Markdown fence;
- no unsupported facts;
- no timestamp/sample-index generation.

Transcript units must preserve deterministic evidence IDs and speaker labels where available.

Units without reliable canonical anchors must remain semantically usable but evidence-ineligible.

### Tests

At minimum:

- speaker-aware prompt;
- no-speaker prompt;
- revision input;
- no-evidence input;
- evidence-ID preservation;
- Chinese default;
- schema instructions;
- timestamp prohibition.

### Gate

Core AI unit tests and Fast PR Gate PASS.

---

## 5. P2 — Direct input budget

### Goal

Reject inputs that are too large for the validated direct-summary path before LiteRT-LM model initialization.

### Implementation

Budget must include:

- fixed prompt overhead;
- structured transcript body;
- evidence markers;
- output reserve;
- safety reserve.

First inspect the LiteRT-LM Android API available in the repository/runtime for a reliable production token-count/tokenizer API.

If suitable API exists, use it.

If it does not, implement a deterministic conservative estimator and calibrate it during real-device QA with the validated Gemma artifact.

Do not use a theoretical model-context value as an unvalidated production limit.

### Failure

Over-budget returns `INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY` before native model initialization.

Forbidden behaviors:

- silent tail truncation;
- prefix-only summary;
- automatic map/reduce;
- starting the native runtime before detecting a known over-budget input.

### Tests

- below limit;
- exact boundary;
- above limit;
- no truncation;
- no model-call invocation when rejected.

### Gate

Unit tests and Fast PR Gate PASS.

---

## 6. P3 — Summary persistence and lifecycle

Primary module: existing database layer.

Room schema: **must remain v16**.

No table/column/migration is authorized.

### Persistence boundary

Add a write-oriented offline-summary persistence abstraction if the current reader repository is not an appropriate ownership boundary.

Required operations include equivalents of:

- create;
- transition state;
- complete;
- fail;
- cancel;
- interrupt;
- load active/non-terminal summaries.

### Lifecycle

```text
CREATED
  -> PREPARING
  -> ANALYZING
  -> VALIDATING
  -> COMPLETED
```

Terminal abnormal states:

```text
FAILED
CANCELLED
INTERRUPTED
```

Do not introduce Stage 17.3 map/reduce states.

### Creation snapshot

At generation start, persist/freeze the input lineage required to identify the exact transcription/revision/canonical audio source.

Each regeneration creates a new summary ID.

Never overwrite an older completed summary row.

### Model identity

Persist a precise local model identity sufficient to trace model ID, version/revision, artifact identity/SHA and LiteRT-LM runtime version without requiring a Room migration.

User-facing formatting can remain concise (`Gemma 4 E2B · 本地`).

### Completion

Only a strictly validated result may transition to `COMPLETED`.

Persist structured payload, deterministic display text, evidence rows, completion metadata and lineage consistently.

### Tests

- lifecycle transitions;
- completion;
- failure;
- cancellation;
- interruption;
- immutable regeneration;
- lineage snapshot;
- model identity;
- Room schema v16 unchanged.

### Gate

Database tests and Fast PR Gate PASS.

---

## 7. P4 — Evidence mapping and display formatter

### Evidence mapping

Build an application-owned map from each structured transcript evidence ref to the canonical source metadata used for playback/evidence persistence.

After model validation, every returned evidence ref must be resolved against that frozen map.

Unknown refs produce `OUTPUT_INVALID_EVIDENCE` and must not be persisted as successful evidence.

The LLM never supplies authoritative sample positions.

### Display text

Implement a deterministic formatter that converts the validated structured summary result to the plain display/search text expected by existing persistence and search/export compatibility.

This formatter belongs in non-Compose core/application code.

### Tests

- known evidence mapping;
- unknown evidence rejection;
- speaker metadata preservation;
- no-evidence units;
- deterministic display text.

### Gate

Unit tests and Fast PR Gate PASS.

---

## 8. P5 — Application-level OfflineSummaryCoordinator

Primary module: app layer.

Introduce `OfflineSummaryCoordinator` (name may adapt to existing naming conventions).

The coordinator must be application-scoped through the existing `AppContainer` application scope, not Activity/Compose ownership.

### Formal execution order

```text
1. duplicate/busy preflight
2. resolve Current Effective Transcription
3. build StructuredTranscriptInput
4. freeze lineage/evidence map
5. create CREATED summary row
6. transition PREPARING
7. verify local model readiness
8. direct-budget preflight
9. render offline summary prompt
10. transition ANALYZING
11. call AppLocalLlmRuntime.generate(AUTO)
12. transition VALIDATING
13. strict decode/validate
14. optional bounded one-time repair
15. strict re-validation
16. evidence mapping
17. atomically/consistently persist COMPLETED result
18. call existing Stage12C/content-version completion hook
19. trigger existing search/index lifecycle as required
20. release feature ownership
```

### Duplicate/global ownership

Use two layers:

- feature-level duplicate summary protection;
- P1 global Local LLM single-owner arbitration.

If P1 reports BUSY, map it to the product-level runtime-busy state rather than creating another engine.

### Critical-task preflight

Summary start must not stop recording, realtime transcription, device realtime audio or other protected heavy speech work.

When an incompatible critical task is active, block summary start with explicit product state/message.

### Tests

- successful direct summary;
- model missing;
- duplicate start;
- runtime busy;
- resource block;
- effective transcript resolved exactly once;
- application-scope ownership.

### Gate

App/coordinator tests and Fast PR Gate PASS.

---

## 9. P6 — Strict validation, one repair, cancellation and restart recovery

### First decode

Raw Gemma output is decoded through the existing strict summary result codec/contract.

Valid output proceeds directly.

Invalid but repairable output may trigger exactly one repair inference.

### Repair request

Repair prompt contains only the data necessary to deterministically repair the frozen result:

- same frozen transcript/input identity;
- invalid original output;
- validation error;
- exact allowed evidence-ref set;
- exact schema requirements.

Repair uses the same formal `AppLocalLlmRuntime` AUTO path.

No second repair is allowed.

### Failure behavior

Second invalid result -> `REPAIR_FAILED`/FAILED.

Never persist partial invalid structured data as a completed summary.

### Cancellation

Coordinator owns the generation job.

User cancel -> coroutine cancellation -> P1 runtime cleanup -> summary terminal state `CANCELLED`.

### Navigation/background

Navigation, tab switch and Activity recreation do not cancel the application-scoped task while the process remains alive.

No new FGS/WorkManager executor is added for this stage.

### Process restart

On app startup, reconcile non-terminal states (`CREATED`, `PREPARING`, `ANALYZING`, `VALIDATING`) to `INTERRUPTED`.

Do not auto-resume or auto-regenerate after process death.

### Tests

- repair success;
- repair failure;
- invalid evidence after repair;
- cancellation cleanup;
- UI detachment does not own cancellation;
- startup reconciliation;
- no duplicate resume.

### Gate

Tests and Fast PR Gate PASS.

---

## 10. P7 — Product UI integration

Modify the existing summary product surface rather than introducing a parallel page architecture.

Likely integration points include the current summary card/detail UI and recording detail product surface.

A lightweight UI state/view-model layer may observe the application coordinator, but it must not own native runtime resources.

### Required UI states

No summary:

```text
从当前转写生成离线总结
内容仅在本机处理
[生成总结]
```

Model missing:

```text
需要安装离线 AI 模型
[前往本地模型]
```

Running states:

- `PREPARING` -> `正在准备`
- `ANALYZING` -> `正在分析`
- `VALIDATING` -> `正在校验`

Running action:

- `取消`

Do not show a fabricated percentage.

Completed state reuses existing summary sections/evidence/history/edit/copy/share/export/search product behavior.

Model display label for a new local result: `Gemma 4 E2B · 本地`.

Regeneration creates a new version/candidate, not an overwrite.

### Gate

UI/state tests and Fast PR Gate PASS.

---

## 11. P8 — Compatibility, privacy and regression automation

### Historical summary compatibility

Verify historical online/provider summaries remain unchanged and readable.

Do not relabel historical summaries as local.

Preserve existing revisions, evidence, version history and current/candidate behavior.

### Privacy/static path gate

Add an automated check or equivalent test proving the Stage 17.2 formal inference path has no dependency on:

- cloud provider execution;
- HTTP AI client;
- API key;
- transcript/audio upload;
- cloud fallback.

ModelManager network use for model installation/update remains a separate lifecycle and must not be conflated with inference.

### Protected baseline regression

Verify no unintended changes to:

- recording/BLE;
- playback;
- ASR/realtime ASR;
- diarization;
- canonical timeline;
- Stage 16X brand/theme;
- Room v16 schema.

### Gate

Full automated regression gate PASS.

---

## 12. P9 — Production-like QA build

After automated implementation gates pass, produce the QA artifact only at the formal QA node.

Build target remains production-like `releaseQa` with:

- minification;
- resource shrink;
- Stage 17 LiteRT-LM R8/JNI verification;
- existing model/security/runtime gates.

The LiteRT-LM keep boundary from P1 remains mandatory.

Artifact retention follows the repository policy: QA artifacts retained for one day.

Normal implementation commits do not generate APK artifacts unnecessarily.

---

## 13. Planned real-device QA order

Real-device QA follows the project hard rule: **one test item per message; do not batch tests.**

Planned sequence:

1. Model-missing summary UI.
2. Installed-model short Chinese transcript generation.
3. Chinese content/structure quality.
4. Evidence mapping and click-to-playback.
5. Speaker-aware meeting transcript.
6. Transcript without speaker alignment.
7. Current Effective Transcription with manual revision.
8. First summary becomes current according to existing lifecycle.
9. Regeneration creates candidate/version.
10. Candidate view/use behavior.
11. Summary manual edit/revision compatibility.
12. Cancellation.
13. Navigate away/return during task.
14. Foreground/background transition.
15. Airplane-mode generation.
16. AUTO normal GPU-preferred path where resource gate allows.
17. Resource preflight CPU selection/fallback path if safely reproducible.
18. Structured-output repair path using a safe QA fixture/injection if available.
19. Direct-budget over-limit rejection.
20. Interrupted/process-restart reconciliation using a safe reproducible QA path.
21. Search/copy/share/TXT/Markdown compatibility.
22. Historical summary compatibility.
23. Repeated generation and memory/resource reclamation.
24. Normal thermal path.

SEVERE/CRITICAL thermal, unsafe native crash injection and other non-reproducible device faults must be recorded as **NOT COVERED / AUTOMATION ONLY** when they cannot be safely triggered. They must not be falsely reported as real-device PASS.

---

## 14. Development commit/gate sequence

```text
P0  docs + frozen contracts
 -> Fast PR Gate

P1  prompt/core contracts
 -> Fast PR Gate

P2  direct input budget
 -> Fast PR Gate

P3  persistence/lifecycle
 -> Fast PR Gate

P4  evidence mapping + display formatter
 -> Fast PR Gate

P5  OfflineSummaryCoordinator
 -> Fast PR Gate

P6  strict validation + one repair + cancellation + restart reconciliation
 -> Fast PR Gate

P7  product UI integration
 -> Fast PR Gate

P8  compatibility/privacy/regression automation
 -> full automated gate

P9  releaseQa + R8/JNI QA build
 -> one-item-at-a-time real-device QA

Closure / Freeze / Handoff
```

Fix failures in the phase that introduces them. Do not accumulate multiple unverified layers before correcting a known regression.

---

## 15. Room schema gate

Every relevant automated gate must confirm Room remains version 16 and exported schema 16 has not drifted due to Stage 17.2.

If implementation discovers a genuine need for a schema change, stop that part of implementation and return to a separate requirements revision. Do not self-authorize Room v17.

---

## 16. Production model-channel gate

Stage 17.2 development/QA continues with the existing Stage 17 validated candidate model contract.

No automatic promotion to the production model manifest is authorized.

After successful Stage 17.2 QA, production model promotion (or continued feature gating) is a separate release decision requiring explicit user confirmation.

---

## 17. Closure artifacts

After all applicable automated and real-device gates pass, create:

- `docs/STAGE_17_2_CLOSURE.md`
- `docs/STAGE_17_2_FREEZE.md`
- `docs/STAGE_17_2_HANDOFF.md`

Closure must record:

- exact final branch HEAD;
- implementation commit list;
- CI run IDs;
- QA APK/run/artifact identity;
- validated model identity;
- prompt version;
- final direct-summary budget and how it was validated;
- backend behavior;
- real-device QA results;
- explicit NOT COVERED paths;
- known limitations.

Stage 17.2 closure does **not** authorize merging PR #37.

---

## 18. Next-stage boundary

Stage 17.3 — Long Transcript Intelligence may add:

```text
long transcript
  -> chunk planning
  -> map
  -> validated intermediate summaries
  -> reduce
  -> checkpoint/recovery
  -> final structured summary
```

Stage 17.2 must not pre-implement that hierarchy.

---

## 19. Authorization state

Requirements V1: **CONFIRMED**

Development Plan V1: **CONFIRMED**

Stage 17.2 implementation: **AUTHORIZED**

PR #37 merge: **NOT AUTHORIZED**
