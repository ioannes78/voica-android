# Stage 17 P1 — Offline LLM Runtime Foundation Handoff

Status: **P1 CLOSED / FROZEN / READY FOR STAGE 17.2 PLANNING**

Repository: `ioannes78/voica-android`

Branch: `stage17-fully-offline-intelligence`

Production baseline: `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`

Validated P1 implementation HEAD before closure docs: `2af4db369286fbf16f28142f871271f412a31003`

PR: `#37` — **DRAFT / OPEN / DO NOT MERGE WITHOUT EXPLICIT USER AUTHORIZATION**

## 1. Handoff purpose

This document is the handoff boundary between Stage 17 P1 — Offline LLM Runtime Foundation and the next product implementation step, Stage 17.2 — Offline Summary V1.

P1 is not a summary feature. It is the frozen local inference substrate that Stage 17.2 must reuse.

## 2. Frozen P1 architecture

### Runtime ownership

- Local LLM runtime ownership is app-level, not Activity/UI-owned.
- Only one heavy Local LLM inference owner may run at a time.
- Runtime state is exposed through the P1 coordinator/state flow.
- Cancellation/success/failure paths must release ownership.
- Stage 17.2 must not create a parallel Engine lifecycle outside the P1 runtime.

### Backend policy

Production default remains `AUTO`.

AUTO semantics:

1. prefer GPU;
2. evaluate capability/resource/thermal gate before generation;
3. use GPU when safe;
4. select CPU when GPU is unsupported or preflight-gated and CPU is allowed;
5. fail explicitly when neither backend is safe/available.

Do not introduce a universal hardcoded RAM/device rule or lock production to the current test device.

Manual GPU/CPU selection remains diagnostic/advanced behavior, not the product default.

### Safe fallback boundary

GPU -> CPU fallback is permitted only for classified pre-generation/recoverable cases such as unsupported/capability/delegate-driver initialization/preflight conditions.

Do not automatically retry on CPU after:

- generation/native crash;
- OOM during active generation;
- severe memory pressure with uncertain runtime release;
- any failure where a prior native runtime may still own resources.

### Model contract

Frozen validated P1 artifact:

- repository: `litert-community/gemma-4-E2B-it-litert-lm`
- revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- file: `gemma-4-E2B-it.litertlm`
- size: `2,583,085,056` bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- runtime: LiteRT-LM Android `0.18.0`

The newer MTP-capable Gemma artifact is not part of the frozen P1 contract.

The Gemma entry remains a Stage 17 candidate and must not be silently promoted into the unrelated production speech model channel as part of P1 closure.

### R8 / JNI contract

The following rule remains mandatory for production-like minified builds:

```proguard
-keep,allowoptimization class com.google.ai.edge.litertlm.** {
    *;
}
```

`ci/verify-litertlm-r8-boundary.sh` must continue to pass for release/releaseQa outputs. Removing or weakening the rule requires a new explicit feasibility/regression gate.

### Isolation contract

Local LLM work must remain outside critical recording/BLE/playback/ASR paths. Stage 17.2 must not make summary generation a dependency for recording finalization, local playback, ASR completion, or device connectivity.

### Network/privacy contract

Stage 17 product intelligence remains offline-first and P1 permits **no cloud fallback**.

Model initial download/update may use network; inference after model installation must not depend on network, provider credentials, cloud APIs, or server availability.

## 3. Accepted QA evidence

P1 real-device QA accepted:

- clean install/model state;
- download/checksum/install/activation;
- restart persistence;
- AUTO -> GPU normal path;
- explicit CPU;
- explicit GPU;
- simulated preflight GPU block -> CPU path;
- single heavy owner;
- cancellation;
- foreground/background transition;
- in-use delete protection;
- delete/reinstall;
- missing-model recovery;
- airplane-mode local inference;
- five consecutive AUTO load/generate/release runs;
- normal memory/resource path;
- normal thermal path.

Explicitly not claimed as real-device covered:

- bad-SHA/corrupt-model fault injection;
- SEVERE/CRITICAL thermal branches.

These are recorded in `docs/STAGE_17_P1_CLOSURE.md` and are not to be rewritten as PASS later without actual evidence.

## 4. Observed performance envelope

Representative accepted observations on the current test device:

- CPU peak PSS: about `1.37 GB`;
- GPU peak PSS: about `2.09–2.25 GB`;
- CPU immediate post-close reclamation is materially better than GPU;
- GPU post-close PSS can remain high temporarily, but repeated-run evidence did not show monotonic per-run growth;
- first/cold GPU initialization can be much slower than warmed runs;
- warmed GPU initialization was observed around 5–7 s in later QA runs;
- generation latency for the fixed probe was a few seconds on both backends.

Stage 17.2 should treat memory/thermal/resource state as runtime inputs rather than assume the test-device envelope is universal.

## 5. Stage 17.2 entry boundary

Stage 17.2 — Offline Summary V1 may add product behavior above the P1 runtime, but must not reopen P1 foundations without an explicit requirement revision.

Expected Stage 17.2 responsibilities:

1. define summary input contract from structured/canonical transcript data;
2. define local summary prompt/schema/output validation;
3. invoke P1 runtime through the existing coordinator/runtime boundary;
4. persist summary product state/versioning through the existing content model only after requirements are confirmed;
5. expose user-visible summary UX consistent with WENZHI Stage 16X themes;
6. handle cancellation/retry without duplicating Engine lifecycle;
7. remain fully local after model installation;
8. add real-device quality and long-input tests appropriate to summary behavior.

Stage 17.2 must not yet expand into:

- hierarchical long-recording intelligence unless separately included by confirmed requirements;
- offline Q&A;
- RAG/embeddings/vector index;
- Audio LLM direct-audio understanding;
- cloud providers/fallback;
- a multi-LLM production matrix.

## 6. Data/time-axis boundary

The Stage 17 architectural rule remains:

**LLM handles semantics; WENZHI owns time coordinates.**

The LLM must not invent or become the authoritative source of playback timestamps. Any future summary evidence/citation identity must be mapped back by the app to canonical transcript/timeline structures.

This becomes especially important for later Stage 17.4 Q&A/audio citation work.

## 7. Next workflow

Before Stage 17.2 code changes:

1. re-verify GitHub `main`, PR #37 HEAD/status, CI and current source;
2. produce/confirm Stage 17.2 requirements;
3. produce/confirm Stage 17.2 development plan;
4. only then implement;
5. run automated gates;
6. run real-device QA one item at a time;
7. close/freeze/handoff the substage before advancing.

PR #37 remains Draft/Open through this handoff. No merge is authorized by P1 closure.