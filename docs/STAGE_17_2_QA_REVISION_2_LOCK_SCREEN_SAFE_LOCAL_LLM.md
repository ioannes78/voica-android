# Stage 17.2 QA Revision-2 — Lock-screen Safe Local LLM

Status: **CONFIRMED / IMPLEMENTING**

Repository: `ioannes78/voica-android`

Branch: `stage17-fully-offline-intelligence`

This revision is a safety response to real-device QA performed after Revision-1 background-execution hardening. It does not change the frozen Gemma 4 E2B model identity, prompt/evidence contract, Room schema, long-transcript boundary, or P1 Local LLM architecture.

## 1. Trigger

Revision-1 real-device QA established the following behavior for Stage 17.2 local summary:

- foreground, screen on: PASS;
- Home/background, screen on: PASS;
- natural screen-off: PASS;
- manual lock: FAIL;
- after the failing lock transition, unlock did not recover generation;
- ordinary cancellation no longer interrupted the stuck native generation;
- force-stop/process restart recovered cleanly and startup reconciliation allowed a new summary;
- a short manual-lock reproduction escalated to app/device freeze followed by an automatic device reboot.

The device reboot is treated as a critical safety signal. Further GPU lock-screen reproduction is suspended until the product path is isolated from the suspected GPU/native interaction.

## 2. Working hypothesis

The evidence is consistent with a LiteRT-LM GPU/native/driver failure triggered by the manual lock-state transition, but the revision does **not** claim root cause as proven yet.

Important observations:

- the Stage 17.2 product preset still entered P1 through `AUTO`;
- `AUTO` prefers GPU when the existing memory/thermal gate allows it;
- the resource gate does not currently model keyguard/lock state;
- the P1 native adapter checks coroutine cancellation while collecting emissions, so a native generation that stops producing emissions can prevent normal cancellation/cleanup from progressing;
- process termination clears the stuck native ownership, after which existing startup reconciliation works.

## 3. Temporary CPU isolation — not a permanent product decision

Revision-2 temporarily forces the current Stage 17.2 structured-output product generation path to CPU.

This is a **diagnostic and safety isolation measure**, not a permanent architectural decision.

The P1 runtime continues to retain:

- `AUTO` policy;
- GPU backend support;
- CPU backend support;
- GPU-first resolver behavior for ordinary unconstrained AUTO calls;
- explicit GPU diagnostic paths;
- explicit CPU diagnostic paths.

Only the current production structured-output request path is transformed from `AUTO` to `CPU` at the app-facing runtime boundary. Explicit diagnostic backend requests are never rewritten.

The isolation is intentionally placed below Stage 17.2 coordinator configuration so the frozen P1 interface and the existing Stage 17.2 prompt/repair flow remain intact. When GPU lock-screen safety is re-validated, the temporary override can be removed without redesigning the coordinator or the P1 resolver.

## 4. Why the isolation is below the coordinator

The Stage 17.2 coordinator still expresses the original product request as `AUTO` and still reuses the sole application-scoped `AppLocalLlmRuntime`.

At `AppLocalLlmRuntime`, Revision-2 recognizes the current constrained-JSON production path and applies a temporary effective policy of `CPU` before model resolution/resource selection/native Engine creation.

This preserves these boundaries:

- no parallel `Engine` or `Conversation` owner;
- no duplicate product runtime;
- no WorkManager inference pipeline;
- no cloud fallback;
- no GPU-to-CPU hot switch during a running generation;
- no retry after an already-stuck native generation.

## 5. Cancellation finding

Revision-2 records an important limitation discovered during QA:

A healthy foreground generation still cancels normally. However, after the manual-lock failure, native generation stopped making observable progress and coroutine cancellation did not unwind the runtime until the process was force-stopped.

Therefore Revision-2 must not attempt an in-flight GPU → CPU switch. Any backend decision must happen before native generation begins.

## 6. Generation-stall diagnostics

The safety plan distinguishes two layers:

1. **Immediate mitigation:** CPU-only isolation for the Stage 17.2 structured product path.
2. **Diagnostics:** retain truthful runtime phase/progress evidence so a future native stall is reported as a stall rather than being mistaken for ordinary long inference.

A watchdog may report long time-to-first-output or long no-progress intervals, but it must not claim that it can safely tear down a native runtime that has already become non-responsive. Process-death reconciliation remains the authoritative recovery for such a failure.

Revision-2 does not introduce fake progress percentages.

## 7. Required automated validation

- existing Stage 17.2 coordinator tests remain green;
- structured AUTO product config is transformed to CPU at the app runtime boundary;
- unconstrained AUTO remains AUTO and therefore retains P1 GPU-first behavior;
- explicit GPU diagnostic requests remain GPU;
- explicit CPU diagnostic requests remain CPU;
- existing LiteRT-LM R8/JNI keep boundary remains unchanged;
- Room remains v16;
- no cloud/network fallback is introduced.

## 8. Real-device validation order

Do not immediately repeat the previous critical lock test.

Validate in this order, one item at a time:

1. CPU-isolated Stage 17.2 summary, foreground/screen-on completion.
2. CPU-isolated summary, Home/background with screen on.
3. CPU-isolated summary, ordinary cancellation.
4. Confirm runtime/result diagnostics report CPU for the product generation.
5. Only after 1–4 pass, perform one controlled manual-lock test.
6. If CPU manual-lock passes, treat GPU/native/driver interaction as the leading root-cause direction and keep GPU lock-screen reproduction suspended pending a dedicated runtime experiment or LiteRT-LM upgrade validation.
7. If CPU manual-lock also fails, stop lock-screen inference QA again and investigate keyguard/FGS/native lifecycle independently of GPU.

## 9. Exit criteria

Revision-2 CPU isolation is temporary.

It may be removed only after a separate real-device gate demonstrates that the intended GPU path survives manual lock/unlock without:

- native generation stall;
- cancellation loss;
- runtime ownership leak;
- app freeze;
- system instability or reboot.

Until then, product safety takes precedence over GPU performance.

PR #37 remains Draft and must not be merged without separate explicit user authorization.
